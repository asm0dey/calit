package site.asm0dey.calit.privacy;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.HashMap;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
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
     * <p>Idempotent: an already-erased booking is left exactly as it was, so the retention
     * scheduler cannot restamp a row the invitee erased last week.
     */
    @Transactional
    public void anonymise(Long bookingId) {
        Booking b = Booking.findById(bookingId);
        if (b == null || b.isErased()) {
            return;
        }
        b.inviteeName = "";
        b.inviteeEmail = "";
        b.answers = new HashMap<>();
        b.meetLink = null;
        b.title = null; // invitee-writable free text; falls back to the meeting type's name
        b.description = null;
        b.erasedAt = Instant.now();
        BookingGuest.delete("bookingId", bookingId);
        Reminder.delete("bookingId = ?1 and sentAt is null", bookingId);
        EmailOutbox.deleteForBooking(bookingId);
        Log.infof("PRIVACY erasure booking=%d", bookingId);
    }

    /**
     * The invitee's own erasure, keyed by the manage token that already proves control of the
     * booking — no new identity verification is introduced.
     *
     * <p>An UPCOMING booking is cancelled first, through the ordinary cancel path, so the Google
     * event is deleted and the owner gets the normal cancellation mail. A past booking is
     * anonymised directly: there is nothing left to cancel.
     */
    public ErasureReport eraseByManageToken(String manageToken) {
        Booking b = QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findByManageToken(manageToken));
        if (b == null || b.isErased()) {
            throw new NotFoundException("No booking for token " + manageToken);
        }
        var hadGoogleEvent = b.googleEventId != null;
        var googleReachable = hadGoogleEvent && calendarPort.isConnected(b.ownerId);

        if (isUpcomingAndHeld(b)) {
            bookingService.cancel(manageToken); // deletes the Google event, mails the owner
        }
        anonymise(b.id);

        var google = !hadGoogleEvent
                ? ErasureReport.GoogleOutcome.NOT_APPLICABLE
                : googleReachable ? ErasureReport.GoogleOutcome.REMOVED : ErasureReport.GoogleOutcome.UNREACHABLE;
        var report = new ErasureReport(
                google, NotificationChannel.count("ownerId", b.ownerId) > 0, /* mail already delivered */ true);
        // Proof of handling: one log line, booking id plus per-destination outcome. No erasure_log
        // table until an operator actually needs an audit trail (ponytail).
        Log.infof(
                "PRIVACY erasure booking=%d google=%s channels=%s mail=%s",
                b.id, report.google(), report.channelsWereUsed(), report.mailWasDelivered());
        return report;
    }

    private static boolean isUpcomingAndHeld(Booking b) {
        return b.endUtc.isAfter(Instant.now())
                && (b.status == BookingStatus.PENDING || b.status == BookingStatus.CONFIRMED);
    }
}
