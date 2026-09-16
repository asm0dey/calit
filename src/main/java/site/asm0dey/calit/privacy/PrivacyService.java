package site.asm0dey.calit.privacy;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.HashMap;
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
import site.asm0dey.calit.scheduler.Reminder;

/**
 * Every read and write that treats personal data AS personal data: erasure, export, account
 * deletion, retention. Explicit Panache queries per table, deliberately — see {@link PersonalData}
 * for why there is no registry interface and no reflection here.
 */
@ApplicationScoped
public class PrivacyService {

    final BookingService bookingService;

    final CalendarPort calendarPort;

    @Inject
    public PrivacyService(BookingService bookingService, CalendarPort calendarPort) {
        this.bookingService = bookingService;
        this.calendarPort = calendarPort;
    }

    /**
     * Blanks one booking's invitee data in place. The ROW survives: the owner keeps a
     * "someone booked 14:00-14:30" record, which is the legitimate-interest half of the balance,
     * and {@code erasedAt} is what 404s the invitee-facing routes afterwards.
     *
     * <p>{@code invitee_email} is NOT NULL, so it becomes an empty string rather than null. That
     * also means the per-email abuse cap ({@code idx_booking_email_created}) can never match an
     * erased row against a real address.
     *
     * <p>Group bookings ({@code groupId != null}) write one row per co-host, each carrying its own
     * copy of the invitee's name, email and answers (see {@code BookingService.bookGroup}). Erasing
     * only the row named by {@code bookingId} would leave every other host's row holding that data
     * indefinitely, so a group booking erases every row sharing that {@code groupId} — mirroring how
     * {@code BookingService.cancel} already treats a group as one unit.
     *
     * <p>Idempotent: an already-erased booking is left exactly as it was, so the retention
     * scheduler cannot restamp a row the invitee erased last week.
     */
    @Transactional
    public void anonymise(Long bookingId) {
        Booking b = Booking.findById(bookingId);
        if (b == null || b.isErased()) {
            return;
        }
        if (b.groupId == null) {
            anonymiseRow(b);
            return;
        }
        for (Booking row : Booking.group(b.groupId)) {
            anonymiseRow(row);
        }
    }

    /** Blanks and stamps one row. Guarded again per-row: a group can never end up half-erased. */
    private static void anonymiseRow(Booking b) {
        if (b.isErased()) {
            return;
        }
        b.inviteeName = "";
        b.inviteeEmail = "";
        b.answers = new HashMap<>();
        b.meetLink = null;
        b.title = null; // invitee-writable free text; falls back to the meeting type's name
        b.description = null;
        b.erasedAt = Instant.now();
        BookingGuest.delete("bookingId", b.id);
        Reminder.delete("bookingId = ?1 and sentAt is null", b.id);
        EmailOutbox.deleteForBooking(b.id);
        Log.infof("PRIVACY erasure booking=%d", b.id);
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
}
