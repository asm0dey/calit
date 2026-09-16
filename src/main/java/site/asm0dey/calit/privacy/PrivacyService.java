package site.asm0dey.calit.privacy;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.notify.NotificationChannel;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.DeletedUsername;

/**
 * Every read and write that treats personal data AS personal data: erasure, export, account
 * deletion, retention. Explicit Panache queries per table, deliberately — see {@link PersonalData}
 * for why there is no registry interface and no reflection here.
 */
@ApplicationScoped
public class PrivacyService {

    final BookingService bookingService;

    final CalendarPort calendarPort;

    final EntityManager em;

    /** What a redacted secret reads as in an export — present, so its existence is disclosed; useless. */
    private static final String REDACTED = "[redacted]";

    @Inject
    public PrivacyService(BookingService bookingService, CalendarPort calendarPort, EntityManager em) {
        this.bookingService = bookingService;
        this.calendarPort = calendarPort;
        this.em = em;
    }

    /**
     * Blanks and stamps every not-yet-erased booking in {@code bookingIds}, and removes its
     * dependents, in exactly two native statements run in this one transaction:
     *
     * <ol>
     *   <li>One UPDATE blanks every personal column of {@code booking} (see {@code
     *       PersonalData}'s "booking" entry — keep the two in sync) and stamps {@code erased_at},
     *       filtered to {@code erased_at IS NULL} so an already-erased row is left exactly as it
     *       was. {@code RETURNING id} reports which rows it actually touched.
     *   <li>One statement of data-modifying CTEs deletes the {@code booking_guest}, unsent {@code
     *       reminder} and {@code email_outbox} rows for exactly those returned ids — never the
     *       full input, so an already-erased id's dependents are not re-deleted. Skipped entirely
     *       when statement 1 touched nothing.
     * </ol>
     *
     * <p>The ROW survives: the owner keeps a "someone booked 14:00-14:30" record, which is the
     * legitimate-interest half of the balance, and {@code erased_at} is what 404s the
     * invitee-facing routes afterwards. {@code invitee_email} is NOT NULL, so it becomes an empty
     * string rather than null — which also means the per-email abuse cap ({@code
     * idx_booking_email_created}) can never match an erased row against a real address.
     *
     * <p>Both statements are native SQL and bypass the Hibernate persistence context: a {@code
     * Booking} entity already loaded (and possibly managed) in this transaction is stale once this
     * call returns — reload it if current state is needed afterward.
     *
     * @return how many bookings were newly erased; 0 for empty input or when every id was already
     *     erased (no SQL runs for empty input)
     */
    @Transactional
    public int anonymise(Collection<Long> bookingIds) {
        if (bookingIds.isEmpty()) {
            return 0;
        }
        List<Long> ids = List.copyOf(bookingIds);

        @SuppressWarnings("unchecked")
        List<Number> erased = em.createNativeQuery("UPDATE booking SET invitee_name = '', invitee_email = '', "
                        + "answers = '{}'::jsonb, meet_link = NULL, title = NULL, description = NULL, "
                        + "erased_at = now() "
                        + "WHERE id IN (:ids) AND erased_at IS NULL "
                        + "RETURNING id")
                .setParameter("ids", ids)
                .getResultList();
        if (erased.isEmpty()) {
            return 0;
        }
        List<Long> erasedIds = erased.stream().map(Number::longValue).toList();

        em.createNativeQuery("WITH g AS (DELETE FROM booking_guest WHERE booking_id IN (:ids)), "
                        + "r AS (DELETE FROM reminder WHERE booking_id IN (:ids) AND sent_at IS NULL) "
                        + "DELETE FROM email_outbox WHERE booking_id IN (:ids)")
                .setParameter("ids", erasedIds)
                .executeUpdate();

        Log.infof("PRIVACY erasure count=%d bookings=%s", erasedIds.size(), erasedIds);
        return erasedIds.size();
    }

    /**
     * Single-booking entry point. Group bookings ({@code groupId != null}) write one row per
     * co-host, each carrying its own copy of the invitee's name, email and answers (see {@code
     * BookingService.bookGroup}). Erasing only the row named by {@code bookingId} would leave every
     * other host's row holding that data indefinitely, so this resolves every row sharing that
     * {@code groupId} — mirroring how {@code BookingService.cancel} already treats a group as one
     * unit — and delegates to {@link #anonymise(Collection)}, which does the actual erasure and is
     * idempotent per id. See that method for the two-statement mechanics and the stale-entity
     * caveat.
     */
    @Transactional
    public void anonymise(Long bookingId) {
        Booking b = Booking.findById(bookingId);
        if (b == null) {
            return;
        }
        List<Long> ids = b.groupId == null
                ? List.of(bookingId)
                : Booking.group(b.groupId).stream().map(row -> row.id).toList();
        anonymise(ids);
    }

    /**
     * The invitee's own erasure, keyed by the manage token that already proves control of the
     * booking — no new identity verification is introduced.
     *
     * <p>An UPCOMING booking is cancelled first, through the ordinary cancel path, so the Google
     * event is deleted and the owner gets the normal cancellation mail. A past booking is
     * anonymised directly: there is nothing left to cancel, so a stored Google event id (the owner's
     * copy the spec still lists as reachable while connected) gets its own best-effort delete here.
     *
     * <p>{@code TxType.NEVER}: this method runs two separate {@code @Transactional} steps below —
     * cancel, then anonymise — that must commit as two separate transactions. If a caller wrapped
     * this call inside its own ambient transaction, both steps would join that one transaction
     * instead, and the {@code BookingCancelled} {@code AFTER_SUCCESS} mail observer would not fire
     * until BOTH steps committed — i.e. after anonymise had already blanked the invitee's name,
     * parking a cancellation mail addressed to "" that survives the outbox purge below it. Refusing
     * an ambient transaction forces cancel's commit (and its mail, addressed while the name is still
     * real) to land before anonymise begins.
     */
    @Transactional(Transactional.TxType.NEVER)
    public ErasureReport eraseByManageToken(String manageToken) {
        Booking b = QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findByManageToken(manageToken));
        if (b == null || b.isErased()) {
            throw new NotFoundException("No booking for that token");
        }

        List<Booking> rows = b.groupId == null
                ? List.of(b)
                : QuarkusTransaction.requiringNew().call(() -> Booking.group(b.groupId));
        // The shared Google event lives on whichever row created it (the group's chosen organizer --
        // BookingService.createGroupGoogleEvent), not necessarily this row: look across the group.
        Booking eventRow =
                rows.stream().filter(r -> r.googleEventId != null).findFirst().orElse(null);

        boolean upcoming = isUpcomingAndHeld(b);
        ErasureReport.GoogleOutcome google;
        if (eventRow == null) {
            google = ErasureReport.GoogleOutcome.NOT_APPLICABLE;
        } else if (upcoming) {
            // cancel() below does the actual delete, gated on the same isConnected check
            // BookingService.cancelSingle/deleteGroupGoogleEvent use -- counted as "ran" per that call.
            google = calendarPort.isConnected(eventRow.ownerId)
                    ? ErasureReport.GoogleOutcome.REMOVED
                    : ErasureReport.GoogleOutcome.UNREACHABLE;
        } else {
            google = bestEffortGoogleDelete(eventRow);
        }

        if (upcoming) {
            bookingService.cancel(manageToken); // deletes the Google event, mails the owner
        }
        anonymise(b.id);

        List<Long> ownerIds = rows.stream().map(r -> r.ownerId).distinct().toList();
        var channelsWereUsed = NotificationChannel.count("ownerId in ?1", ownerIds) > 0;
        var report = new ErasureReport(google, channelsWereUsed, /* mail already delivered */ true);
        // Proof of handling: one log line, booking id plus per-destination outcome. No erasure_log
        // table until an operator actually needs an audit trail (ponytail).
        Log.infof(
                "PRIVACY erasure booking=%d google=%s channels=%s mail=%s",
                b.id, report.google(), report.channelsWereUsed(), report.mailWasDelivered());
        return report;
    }

    /**
     * Past bookings are never cancelled, so nothing else calls {@code CalendarPort.deleteEvent} for
     * them; a stored event id still means the owner's Google copy exists, and the spec lists it as
     * reachable while connected. Mirrors {@code BookingService.cancelSingle}'s connected-gated
     * delete, but — unlike cancel, which lets a Google failure roll back the whole cancellation —
     * catches rather than propagates: the invitee's own erasure of calit's copy must still complete
     * even when the remote call fails.
     */
    private ErasureReport.GoogleOutcome bestEffortGoogleDelete(Booking eventRow) {
        if (!calendarPort.isConnected(eventRow.ownerId)) {
            return ErasureReport.GoogleOutcome.UNREACHABLE;
        }
        try {
            calendarPort.deleteEvent(eventRow.ownerId, eventRow.calendarRef(), eventRow.googleEventId);
            return ErasureReport.GoogleOutcome.REMOVED;
        } catch (RuntimeException e) {
            Log.warnf(
                    e,
                    "PRIVACY erasure booking=%d could not delete Google event %s",
                    eventRow.id,
                    eventRow.googleEventId);
            return ErasureReport.GoogleOutcome.UNREACHABLE;
        }
    }

    private static boolean isUpcomingAndHeld(Booking b) {
        return b.endUtc.isAfter(Instant.now())
                && (b.status == BookingStatus.PENDING || b.status == BookingStatus.CONFIRMED);
    }

    /**
     * Everything calit holds about ONE booking, from the invitee's side of it: the times, the
     * meeting, their own name/email/answers, and their guest list. Art. 15 access, scoped to what
     * the manage token authorizes — it proves control of this booking and nothing else, so this is
     * not a search across every booking that shares an address.
     *
     * <p>The 404 message deliberately omits the token: unlike a booking id, the manage token is a
     * bearer credential, and echoing it back would put it in logs/error pages for no benefit.
     *
     * <p>Built as ONE native {@code json_build_object}/{@code json_agg} query (R14), keyed by the
     * manage token with {@code erased_at IS NULL} standing in for the old {@code b.isErased()}
     * check. {@code meetingType} reproduces {@link Booking#effectiveTitle} in SQL: the booking's own
     * {@code title} wins when it is non-null and non-blank, else the meeting type's name. Returned as
     * raw JSON text — see {@link #exportOwner} for why that is safe for a route to serve verbatim as
     * {@code application/json}.
     */
    public String exportBooking(String manageToken) {
        List<?> rows =
                em.createNativeQuery("""
                        SELECT json_build_object(
                            'exportedAt', now(),
                            'booking', json_build_object(
                                'id', b.id,
                                'meetingType', CASE WHEN nullif(trim(b.title), '') IS NOT NULL
                                                     THEN b.title ELSE mt.name END,
                                'title', b.title,
                                'description', b.description,
                                'startUtc', b.start_utc,
                                'endUtc', b.end_utc,
                                'status', b.status,
                                'locale', b.locale,
                                'createdAt', b.created_at
                            ),
                            'invitee', json_build_object(
                                'name', b.invitee_name,
                                'email', b.invitee_email,
                                'answers', b.answers
                            ),
                            'guests', coalesce((
                                SELECT json_agg(json_build_object('email', g.email, 'status', g.status)
                                                 ORDER BY g.id)
                                FROM booking_guest g WHERE g.booking_id = b.id
                            ), '[]'::json)
                        )::text
                        FROM booking b
                        JOIN meeting_type mt ON mt.id = b.meeting_type_id
                        WHERE b.manage_token = :token AND b.erased_at IS NULL
                        """).setParameter("token", manageToken).getResultList();
        if (rows.isEmpty()) {
            throw new NotFoundException("No booking for that token");
        }
        return (String) rows.get(0);
    }

    /**
     * Art. 15/20 for the owner: one JSON file covering their whole subtree. Bookings include the
     * invitee data — the OWNER is the controller for it, so an export that hid it would be useless
     * for the Art. 30 records they have to keep.
     *
     * <p>Two things are deliberately withheld. The argon2id password hash is a credential, not
     * personal data the subject needs back — {@code hasPassword} discloses only whether one is set.
     * Notification-channel URLs are bearer secrets — the URL IS the authorization to post into that
     * chat, so a channel row is disclosed as present and its {@code url} redacted to {@link
     * #REDACTED} as a value. Google OAuth tokens are withheld for the same reason; a connected
     * account reports only its {@code email}.
     *
     * <p>Built as ONE native {@code json_build_object}/{@code json_agg} query (R14): every subquery
     * filters by {@code :ownerId} (the owner-scoping invariant, enforced in SQL rather than in Java
     * here), secrets are excluded by explicit column lists — never {@code password_hash}, {@code
     * access_token}, {@code refresh_token}, or OAuth state — an empty collection coalesces to {@code
     * '[]'} and a missing {@code owner_settings} row to {@code '{}'}. Returned as raw JSON text:
     * Quarkus's Jackson server writer special-cases {@link String} and writes it byte-for-byte
     * instead of re-encoding it as a JSON string literal, so the route can serve this directly as
     * {@code application/json} without a second serialization pass.
     */
    public String exportOwner(Long ownerId) {
        return (String) em.createNativeQuery("""
                        SELECT json_build_object(
                            'exportedAt', now(),
                            'account', (
                                SELECT json_build_object(
                                    'username', u.username,
                                    'isAdmin', u.is_admin,
                                    'enabled', u.enabled,
                                    'hasPassword', u.password_hash IS NOT NULL,
                                    'linkedGoogle', u.google_sub IS NOT NULL,
                                    'linkedOidc', u.oidc_sub IS NOT NULL,
                                    'createdAt', u.created_at
                                )
                                FROM app_user u WHERE u.id = :ownerId
                            ),
                            'settings', coalesce((
                                SELECT json_build_object(
                                    'ownerName', s.owner_name,
                                    'ownerEmail', s.owner_email,
                                    'timezone', s.timezone,
                                    'locale', s.locale,
                                    'timeFormat', s.time_format,
                                    'ownerNotificationsEnabled', s.owner_notifications_enabled,
                                    'bookingRetentionDays', s.booking_retention_days
                                )
                                FROM owner_settings s WHERE s.owner_id = :ownerId
                            ), '{}'::json),
                            'meetingTypes', coalesce((
                                SELECT json_agg(json_build_object(
                                    'id', t.id, 'name', t.name, 'slug', t.slug,
                                    'description', coalesce(t.description, '')
                                ) ORDER BY t.id)
                                FROM meeting_type t WHERE t.owner_id = :ownerId
                            ), '[]'::json),
                            'availability', coalesce((
                                SELECT json_agg(json_build_object(
                                    'dayOfWeek', r.day_of_week,
                                    'startTime', r.start_time,
                                    'endTime', r.end_time
                                ) ORDER BY r.id)
                                FROM availability_rule r WHERE r.owner_id = :ownerId
                            ), '[]'::json),
                            'dateOverrides', coalesce((
                                SELECT json_agg(json_build_object('date', d.override_date) ORDER BY d.id)
                                FROM date_override d WHERE d.owner_id = :ownerId
                            ), '[]'::json),
                            'bookings', coalesce((
                                SELECT json_agg(json_build_object(
                                    'id', b.id,
                                    'startUtc', b.start_utc,
                                    'endUtc', b.end_utc,
                                    'status', b.status,
                                    'erasedAt', b.erased_at,
                                    'inviteeName', b.invitee_name,
                                    'inviteeEmail', b.invitee_email,
                                    'answers', b.answers,
                                    'guests', coalesce((
                                        SELECT json_agg(g.email ORDER BY g.id)
                                        FROM booking_guest g WHERE g.booking_id = b.id
                                    ), '[]'::json)
                                ) ORDER BY b.id)
                                FROM booking b WHERE b.owner_id = :ownerId
                            ), '[]'::json),
                            'notificationChannels', coalesce((
                                SELECT json_agg(json_build_object(
                                    'label', coalesce(c.label, ''), 'url', :redacted
                                ) ORDER BY c.id)
                                FROM notification_channel c WHERE c.owner_id = :ownerId
                            ), '[]'::json),
                            'googleAccounts', coalesce((
                                SELECT json_agg(json_build_object(
                                    'email', coalesce(g.account_email, '')
                                ) ORDER BY g.id)
                                FROM google_credential g WHERE g.owner_id = :ownerId
                            ), '[]'::json)
                        )::text
                        """)
                .setParameter("ownerId", ownerId)
                .setParameter("redacted", REDACTED)
                .getSingleResult();
    }

    /**
     * Admins who can still log in. Driving this to zero locks everyone out with no in-app recovery.
     * Advisory only (no lock) — safe as a UI pre-check, but {@link #deleteAccount} re-checks under a
     * pessimistic lock rather than trusting this read, since two concurrent deletions of two
     * different enabled admins could otherwise both observe "count > 1" and both proceed.
     */
    public boolean isLastEnabledAdmin(Long userId) {
        AppUser u = AppUser.findById(userId);
        return u != null && u.isAdmin && u.enabled && AppUser.count("isAdmin = true and enabled = true") <= 1;
    }

    /**
     * Art. 17 for an owner: the account row goes, and every {@code owner_id} cascade takes the
     * subtree with it — settings, meeting types, availability, bookings, guests, Google credentials
     * and calendars, notification channels, reset tokens, login tickets.
     *
     * <p>{@code email_outbox} is purged explicitly first even though V34 gave it a cascading {@code
     * owner_id}: rows enqueued BEFORE V34 carry a null link and would otherwise outlive the account.
     * The explicit delete is a no-op for those, so the 30-day age purge remains their only route —
     * which is why the operator guide names that window.
     *
     * <p>The username is tombstoned ({@link DeletedUsername}, R16) BEFORE the row is deleted, in the
     * same transaction: form-auth's persistent-login cookie carries only a username and keeps
     * renewing itself even after the backing account is gone, so a deleted name must never become
     * choosable again — otherwise a stale cookie from this account would silently authenticate as
     * whoever re-registers the name.
     *
     * <p>No Google revoke: {@code GooglePageResource.disconnect} never called Google's revoke
     * endpoint either, so deleting the credential rows removes calit's copy of the tokens without
     * withdrawing the grant at Google. The privacy copy and the operator guide both say so.
     *
     * <p>No "your account was deleted" email — the mailbox may be the thing being erased.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        AppUser u = AppUser.findById(userId);
        if (u == null) {
            return;
        }
        if (u.isAdmin && u.enabled) {
            // Pessimistic lock on every enabled-admin row before counting: closes the race where
            // two concurrent deletions of two DIFFERENT enabled admins could each read "more than
            // one enabled admin" and both proceed, leaving zero.
            List<AppUser> enabledAdmins = AppUser.<AppUser>find("isAdmin = true and enabled = true")
                    .withLock(LockModeType.PESSIMISTIC_WRITE)
                    .list();
            if (enabledAdmins.size() <= 1) {
                throw new IllegalStateException("last-admin");
            }
        }
        EmailOutbox.deleteForOwner(userId);
        DeletedUsername.tombstone(u.username);
        u.delete();
        Log.infof("PRIVACY account-deleted user=%d", userId);
    }
}
