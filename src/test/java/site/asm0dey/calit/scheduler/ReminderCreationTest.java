package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;

@QuarkusTest
class ReminderCreationTest {

    @Inject
    ReminderScheduler scheduler;

    // Lead time default is 1440 min (24h).

    @Test
    void scheduleReminderInsertsRowOneLeadBeforeStart() {
        Long id = seedBooking(Instant.now().plus(48, ChronoUnit.HOURS), BookingStatus.CONFIRMED);

        scheduler.scheduleReminder(id);

        Reminder r = QuarkusTransaction.requiringNew()
                .call(() -> Reminder.find("bookingId", id).firstResult());
        assertEquals(Reminder.KIND_REMINDER, r.kind);
        // start in 48h, lead 24h -> sendAt ~ 24h from now (well in the future).
        assertTrue(r.sendAt.isAfter(Instant.now().plus(23, ChronoUnit.HOURS)));
        assertTrue(r.sendAt.isBefore(Instant.now().plus(25, ChronoUnit.HOURS)));
    }

    @Test
    void scheduleReminderSkipsWhenSendAtAlreadyPast() {
        // Booking starts in 1h but lead is 24h -> sendAt is in the past -> no row.
        Long id = seedBooking(Instant.now().plus(1, ChronoUnit.HOURS), BookingStatus.CONFIRMED);

        scheduler.scheduleReminder(id);

        long count = QuarkusTransaction.requiringNew().call(() -> Reminder.count("bookingId", id));
        assertEquals(0, count);
    }

    @Test
    void scheduleReminderIsIdempotentReplacingUnsentRow() {
        Long id = seedBooking(Instant.now().plus(72, ChronoUnit.HOURS), BookingStatus.CONFIRMED);

        scheduler.scheduleReminder(id);
        scheduler.scheduleReminder(id); // re-confirm: must NOT create a second unsent row

        long count =
                QuarkusTransaction.requiringNew().call(() -> Reminder.count("bookingId = ?1 and sentAt is null", id));
        assertEquals(1, count);
    }

    @Test
    void cancelDeletesUnsentReminder() {
        Long id = seedBooking(Instant.now().plus(96, ChronoUnit.HOURS), BookingStatus.CONFIRMED);
        scheduler.scheduleReminder(id);

        scheduler.onCancelledOrDeclined(id);

        long count =
                QuarkusTransaction.requiringNew().call(() -> Reminder.count("bookingId = ?1 and sentAt is null", id));
        assertEquals(0, count);
    }

    // These bookings/reminders are COMMITTED (no @TestTransaction). Each test uses a distinct
    // start base (48h/1h/72h/96h) so their [start,end) ranges never overlap on the
    // booking_no_overlap_held exclusion guard. Cleaned up after each test so rows don't leak.
    // (Plan 6 deviation: the plan's seedBooking used meetingTypeId=1L with no manageToken,
    // which FK-/NOT-NULL-violates; here we create a real MeetingType and set manageToken.)
    private final java.util.List<Long> createdBookingIds = new java.util.ArrayList<>();
    private final java.util.List<Long> createdMeetingTypeIds = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (Long id : createdBookingIds) {
                Reminder.delete("bookingId", id);
                Booking.deleteById(id);
            }
            for (Long id : createdMeetingTypeIds) {
                MeetingType.deleteById(id);
            }
        });
        createdBookingIds.clear();
        createdMeetingTypeIds.clear();
    }

    private Long seedBooking(Instant startUtc, BookingStatus status) {
        var out = new Long[2];
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "create-" + System.nanoTime();
            t.slug = "create-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            out[0] = t.id;
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = startUtc;
            b.endUtc = startUtc.plusSeconds(1800);
            b.status = status;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            out[1] = b.id;
            return b.id;
        });
        createdMeetingTypeIds.add(out[0]);
        createdBookingIds.add(out[1]);
        return id;
    }
}
