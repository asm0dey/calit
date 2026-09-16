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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.OwnerSettings;
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
     * <p>The token resolves through {@link Booking#findLiveForPrivacy}: a group whose token row was
     * already erased by one host's retention window is still reachable while any co-host row holds
     * the invitee's data, and 404s only once every row is erased. Every remaining row of the group
     * is erased.
     *
     * <p>An UPCOMING booking is cancelled first, through the ordinary cancel path, so the Google
     * event is deleted and the owner gets the normal cancellation mail — in its Google-tolerant
     * variant: an unreachable Google must not block the invitee's erasure, so a failed delete is
     * logged, the cancellation still commits, and the report says UNREACHABLE. A past booking is
     * anonymised directly: there is nothing left to cancel, so a stored Google event id (the owner's
     * copy the spec still lists as reachable while connected) gets its own best-effort delete here,
     * and the row's event refs are cleared in the anonymising transaction either way — same rule as
     * cancel (calit-ek26): an erased row must not keep pointing at the owner's event.
     *
     * <p>{@code TxType.NEVER}: this method runs two separate transactions below — cancel, then
     * anonymise — that must commit separately. If a caller wrapped this call inside its own ambient
     * transaction, both steps would join that one transaction instead, and the {@code
     * BookingCancelled} {@code AFTER_SUCCESS} mail observer would not fire until BOTH steps
     * committed — i.e. after anonymise had already blanked the invitee's name, parking a
     * cancellation mail addressed to "" that survives the outbox purge below it. Refusing an ambient
     * transaction forces cancel's commit (and its mail, addressed while the name is still real) to
     * land before anonymise begins.
     */
    @Transactional(Transactional.TxType.NEVER)
    public ErasureReport eraseByManageToken(String manageToken) {
        Booking live = QuarkusTransaction.requiringNew().call(() -> Booking.findLiveForPrivacy(manageToken));
        if (live == null) {
            throw new NotFoundException("No booking for that token");
        }

        List<Booking> rows = live.groupId == null
                ? List.of(live)
                : QuarkusTransaction.requiringNew().call(() -> Booking.group(live.groupId));
        List<Long> ids = rows.stream().map(r -> r.id).toList();
        // The shared Google event lives on whichever row created it (the group's chosen organizer --
        // BookingService.createGroupGoogleEvent), not necessarily this row: look across the group.
        Booking eventRow =
                rows.stream().filter(r -> r.googleEventId != null).findFirst().orElse(null);

        ErasureReport.GoogleOutcome google;
        if (isUpcomingAndHeld(live)) {
            // Deletes the Google event (best effort) and mails the owner, committed before anonymise.
            boolean deleted = bookingService.cancelToleratingGoogleFailure(live.manageToken, false);
            google = googleOutcome(eventRow, deleted);
        } else {
            google = eventRow == null ? ErasureReport.GoogleOutcome.NOT_APPLICABLE : bestEffortGoogleDelete(eventRow);
        }

        QuarkusTransaction.requiringNew().run(() -> {
            anonymise(ids);
            if (eventRow != null) {
                Booking.update(
                        "googleEventId = null, googleCalendarId = null, googleCredentialId = null where id in ?1", ids);
            }
        });

        List<Long> ownerIds = rows.stream().map(r -> r.ownerId).distinct().toList();
        var channelsWereUsed = NotificationChannel.count("ownerId in ?1", ownerIds) > 0;
        var report = new ErasureReport(google, channelsWereUsed, /* mail already delivered */ true);
        // Proof of handling: one log line, booking id plus per-destination outcome. No erasure_log
        // table until an operator actually needs an audit trail (ponytail).
        Log.infof(
                "PRIVACY erasure booking=%d google=%s channels=%s mail=%s",
                live.id, report.google(), report.channelsWereUsed(), report.mailWasDelivered());
        return report;
    }

    private static ErasureReport.GoogleOutcome googleOutcome(Booking eventRow, boolean deleted) {
        if (eventRow == null) {
            return ErasureReport.GoogleOutcome.NOT_APPLICABLE;
        }
        return deleted ? ErasureReport.GoogleOutcome.REMOVED : ErasureReport.GoogleOutcome.UNREACHABLE;
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
     * manage token. The exported row is the token's own row while it is not erased, else any
     * not-erased row of the same group (see {@link Booking#findLiveForPrivacy} for why a group can
     * be partly erased); 404 only when no such row is left. {@code meetingType} reproduces {@link
     * Booking#effectiveTitle} in SQL: the booking's own {@code title} wins when it is non-null and
     * non-blank, else the meeting type's name. Returned as
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
                        FROM booking t
                        JOIN booking b ON b.id = t.id OR (t.group_id IS NOT NULL AND b.group_id = t.group_id)
                        JOIN meeting_type mt ON mt.id = b.meeting_type_id
                        WHERE t.manage_token = :token AND b.erased_at IS NULL
                        ORDER BY (b.id = t.id) DESC, b.id
                        LIMIT 1
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
     * <p>Upcoming bookings on the account's OWN meeting types are cancelled first. The {@code
     * meeting_type_id} cascade would otherwise delete them silently — including co-hosts' rows of a
     * group booking, which carry the creator's type — with no mail to the invitee or co-hosts and
     * the Google event left behind. Each is cancelled through {@link
     * BookingService#cancelToleratingGoogleFailure} (a Google outage must not block deletion), one
     * group once, each in its own committed transaction, so the {@code AFTER_SUCCESS} cancellation
     * observers render their mails from real rows and send them before anything is deleted. Parked
     * copies of those cancellation notices would die with the account — the invitee's and guests'
     * copies are tagged with this owner and the booking, the co-hosts' with their own cascading
     * booking row — so the final transaction drops those booking links first (a co-host copy keeps
     * its co-host owner link; the rest fall to the 30-day age purge, like a pre-V34 row). The
     * deleted owner's own copy stays tagged and is purged with the account.
     *
     * <p>{@code TxType.NEVER} for the same reason as {@link #eraseByManageToken}: inside an ambient
     * transaction the cancellation mails would fire only after the rows they read were deleted.
     *
     * <p>The last-admin rule is checked twice: an advisory read before anything is cancelled, so the
     * common refusal leaves the account's bookings alone, and again under a pessimistic lock in the
     * final transaction, which is the real guard. Two concurrent deletions of the last two enabled
     * admins can both pass the advisory read and cancel their bookings; the lock then refuses one of
     * them, which keeps its account but not its cancelled bookings. That is the accepted price of
     * sending the notices from committed data.
     *
     * <p>The final transaction is atomic: the locked admin check, the outbox purge, the username
     * tombstone and the row delete commit together or not at all. {@code email_outbox} is purged
     * explicitly even though V34 gave it a cascading {@code owner_id}: rows enqueued BEFORE V34 carry
     * a null link and would otherwise outlive the account. The explicit delete is a no-op for those,
     * so the 30-day age purge remains their only route — which is why the operator guide names that
     * window.
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
     *
     * @throws IllegalStateException {@code "last-admin"} when this is the last enabled admin
     */
    @Transactional(Transactional.TxType.NEVER)
    public void deleteAccount(Long userId) {
        boolean exists = QuarkusTransaction.requiringNew().call(() -> {
            if (AppUser.findById(userId) == null) {
                return false;
            }
            if (isLastEnabledAdmin(userId)) {
                throw new IllegalStateException("last-admin");
            }
            return true;
        });
        if (!exists) {
            return;
        }

        long outboxMarker = QuarkusTransaction.requiringNew()
                .call(() -> ((Number) em.createNativeQuery("SELECT coalesce(max(id), 0) FROM email_outbox")
                                .getSingleResult())
                        .longValue());
        List<Booking> held = QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>list(
                        "meetingTypeId in (select t.id from MeetingType t where t.ownerId = ?1) "
                                + "and endUtc > ?2 and status in ?3 and erasedAt is null order by id",
                        userId,
                        Instant.now(),
                        List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED)));
        Set<UUID> groupsDone = new HashSet<>();
        for (Booking b : held) {
            if (b.groupId != null && !groupsDone.add(b.groupId)) {
                continue; // the whole group was cancelled through its first row
            }
            try {
                bookingService.cancelToleratingGoogleFailure(b.manageToken, true);
            } catch (NotFoundException e) {
                // erased between the read and the cancel: nothing left to notify about
            }
        }
        List<Long> cancelledIds = held.stream().map(b -> b.id).toList();

        QuarkusTransaction.requiringNew().run(() -> deleteAccountRow(userId, outboxMarker, cancelledIds));
    }

    /** The atomic half of {@link #deleteAccount}: locked admin check, outbox, tombstone, delete. */
    private void deleteAccountRow(Long userId, long outboxMarker, List<Long> cancelledIds) {
        AppUser u = AppUser.findById(userId);
        if (u == null) {
            return;
        }
        if (u.isAdmin && u.enabled) {
            // Pessimistic lock on every enabled-admin row before counting: closes the race where
            // two concurrent deletions of two DIFFERENT enabled admins could each read "more than
            // one enabled admin" and both proceed, leaving zero. Ordered so concurrent lockers take
            // the rows in the same order and cannot deadlock each other.
            List<AppUser> enabledAdmins = AppUser.<AppUser>find("isAdmin = true and enabled = true order by id")
                    .withLock(LockModeType.PESSIMISTIC_WRITE)
                    .list();
            if (enabledAdmins.size() <= 1) {
                throw new IllegalStateException("last-admin");
            }
        }
        if (!cancelledIds.isEmpty()) {
            OwnerSettings settings = OwnerSettings.forOwner(userId);
            em.createNativeQuery("UPDATE email_outbox SET booking_id = NULL, "
                            + "owner_id = CASE WHEN owner_id = :uid THEN NULL ELSE owner_id END "
                            + "WHERE id > :marker AND booking_id IN (:ids) "
                            + "AND NOT (owner_id IS NOT DISTINCT FROM :uid AND recipient = :ownerEmail)")
                    .setParameter("uid", userId)
                    .setParameter("marker", outboxMarker)
                    .setParameter("ids", cancelledIds)
                    .setParameter(
                            "ownerEmail", settings == null || settings.ownerEmail == null ? "" : settings.ownerEmail)
                    .executeUpdate();
        }
        EmailOutbox.deleteForOwner(userId);
        DeletedUsername.tombstone(u.username);
        u.delete();
        Log.infof("PRIVACY account-deleted user=%d cancelled-booking-rows=%d", userId, cancelledIds.size());
    }
}
