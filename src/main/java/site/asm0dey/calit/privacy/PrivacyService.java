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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
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
     */
    public Map<String, Object> exportBooking(String manageToken) {
        Booking b = Booking.findByManageToken(manageToken);
        if (b == null || b.isErased()) {
            throw new NotFoundException("No booking for that token");
        }
        MeetingType type = MeetingType.findById(b.meetingTypeId);
        var booking = new LinkedHashMap<String, Object>();
        booking.put("id", b.id);
        booking.put("meetingType", b.effectiveTitle(type));
        booking.put("title", b.title);
        booking.put("description", b.description);
        booking.put("startUtc", b.startUtc.toString());
        booking.put("endUtc", b.endUtc.toString());
        booking.put("status", b.status.name());
        booking.put("locale", b.locale);
        booking.put("createdAt", b.createdAt.toString());

        var invitee = new LinkedHashMap<String, Object>();
        invitee.put("name", b.inviteeName);
        invitee.put("email", b.inviteeEmail);
        invitee.put("answers", b.answers);

        var guests = BookingGuest.allForBooking(b.id).stream()
                .map(g -> Map.<String, Object>of("email", g.email, "status", g.status.name()))
                .toList();

        var out = new LinkedHashMap<String, Object>();
        out.put("exportedAt", Instant.now().toString());
        out.put("booking", booking);
        out.put("invitee", invitee);
        out.put("guests", guests);
        return out;
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
