package site.asm0dey.calit.scheduler;

import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ReminderTest {

    @Test
    @TestTransaction
    void persistsAndReadsBackAllFields() {
        // reminder.booking_id REFERENCES booking(id), so seed a real booking first (Plan 6 deviation).
        Long bookingId = seedBookingAt(uniqueFutureStart());
        Instant sendAt = Instant.parse("2026-06-07T07:00:00Z");
        Reminder r = new Reminder();
        r.bookingId = bookingId;
        r.sendAt = sendAt;
        r.kind = Reminder.KIND_REMINDER;
        r.sentAt = null;
        r.persist();

        Reminder loaded = Reminder.findById(r.id);
        assertEquals(bookingId, loaded.bookingId);
        assertEquals(sendAt, loaded.sendAt);
        assertEquals("REMINDER", loaded.kind);
        assertNull(loaded.sentAt);
    }

    @Test
    @TestTransaction
    void deleteUnsentForRemovesOnlyUnsentRowsOfThatBooking() {
        // reminder.booking_id REFERENCES booking(id), so seed real bookings (Plan 6 deviation).
        // Distinct unique far-future slots so the booking_no_overlap_held exclusion guard doesn't
        // trip -- including against bookings other test classes leave committed.
        Long a = seedBookingAt(uniqueFutureStart());
        Long b = seedBookingAt(uniqueFutureStart());
        Instant base = Instant.parse("2026-06-07T07:00:00Z");
        persist(a, base, null);                 // unsent for a -> deleted
        persist(a, base, base.minusSeconds(1)); // already sent for a -> kept
        persist(b, base, null);                 // unsent for a different booking -> kept

        Reminder.deleteUnsentFor(a);

        assertEquals(0, Reminder.count("bookingId = ?1 and sentAt is null", a));
        assertEquals(1, Reminder.count("bookingId = ?1 and sentAt is not null", a));
        assertEquals(1, Reminder.count("bookingId = ?1 and sentAt is null", b));
    }

    // A unique far-future start instant (seconds-aligned), so seeded HELD bookings never collide
    // on booking_no_overlap_held with each other or with rows other test classes leave committed.
    private static final java.util.concurrent.atomic.AtomicLong SLOT =
            new java.util.concurrent.atomic.AtomicLong(0);

    private Instant uniqueFutureStart() {
        // Years into the future, stepped by an hour per call -> guaranteed non-overlapping.
        return Instant.now().plus(3650, ChronoUnit.DAYS).plus(SLOT.getAndIncrement(), ChronoUnit.HOURS);
    }

    private Long seedBookingAt(Instant start) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "rem-" + System.nanoTime();
        t.slug = "rem-" + System.nanoTime();
        t.durationMinutes = 30;
        t.persist();
        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = t.id;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = start;
        b.endUtc = start.plusSeconds(1800);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.persist();
        return b.id;
    }

    private void persist(Long bookingId, Instant sendAt, Instant sentAt) {
        Reminder r = new Reminder();
        r.bookingId = bookingId;
        r.sendAt = sendAt;
        r.kind = Reminder.KIND_REMINDER;
        r.sentAt = sentAt;
        r.persist();
    }
}
