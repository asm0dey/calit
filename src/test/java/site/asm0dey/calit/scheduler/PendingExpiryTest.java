package site.asm0dey.calit.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class PendingExpiryTest {

    @Inject
    PendingExpiryScheduler scheduler;

    // Hold window default is 24h.

    // Other test classes leave HELD bookings committed at near-"now" slots; clear them so the
    // soon-start / past-start seeds here never collide on the booking_no_overlap_held guard.
    // (Reminders cascade-delete with their booking.) Plan 6 deviation.
    @org.junit.jupiter.api.BeforeEach
    void clearBookings() {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking.deleteAll();
            // The expiry tick fires BookingDeclined, which Plan 4's EmailService observes and which
            // reads the OwnerSettings singleton. In production it always exists; seed it here so the
            // post-commit observer doesn't NPE (the failure is swallowed, but it pollutes the log).
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();
        });
    }

    // A unique far-future start instant so seeded HELD bookings never overlap one another.
    private static final java.util.concurrent.atomic.AtomicLong SLOT =
            new java.util.concurrent.atomic.AtomicLong(0);

    private Instant uniqueFutureStart() {
        return Instant.now().plus(3650, ChronoUnit.DAYS).plus(SLOT.getAndIncrement(), ChronoUnit.HOURS);
    }

    @Test
    void expiresPendingPastHoldButLeavesFreshPendingAndOtherStatuses() {
        Long meetingTypeId = seedMeetingType();

        // Created 25h ago, start far future -> hold (createdAt+24h) elapsed -> expire.
        Long expired = seedBooking(meetingTypeId,
                Instant.now().minus(25, ChronoUnit.HOURS),
                uniqueFutureStart(),
                BookingStatus.PENDING);

        // Created 1h ago, start far future -> within hold -> keep.
        Long fresh = seedBooking(meetingTypeId,
                Instant.now().minus(1, ChronoUnit.HOURS),
                uniqueFutureStart(),
                BookingStatus.PENDING);

        // Created 1h ago but starts in 30 MIN -> min(createdAt+24h, startUtc)=startUtc>now -> keep.
        Long soonStart = seedBooking(meetingTypeId,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(30, ChronoUnit.MINUTES),
                BookingStatus.PENDING);

        // A CONFIRMED booking with an old createdAt must NOT be touched (only PENDING expires).
        Long confirmed = seedBooking(meetingTypeId,
                Instant.now().minus(48, ChronoUnit.HOURS),
                uniqueFutureStart(),
                BookingStatus.CONFIRMED);

        scheduler.expirePendingBookings();

        assertEquals(BookingStatus.DECLINED, reloadStatus(expired));
        assertEquals(BookingStatus.PENDING, reloadStatus(fresh));
        assertEquals(BookingStatus.PENDING, reloadStatus(soonStart));
        assertEquals(BookingStatus.CONFIRMED, reloadStatus(confirmed));
    }

    @Test
    void expiresPendingPastStartEvenWithinHoldWindow() {
        Long meetingTypeId = seedMeetingType();
        // Created 1h ago (within 24h hold) but START already passed -> min(...)=startUtc<=now -> expire.
        Long pastStart = seedBooking(meetingTypeId,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().minus(10, ChronoUnit.MINUTES),
                BookingStatus.PENDING);

        scheduler.expirePendingBookings();

        assertEquals(BookingStatus.DECLINED, reloadStatus(pastStart));
    }

    private Long seedMeetingType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "expiry-" + System.nanoTime();
            t.slug = "expiry-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            return t.id;
        });
    }

    private Long seedBooking(Long meetingTypeId, Instant createdAt, Instant startUtc, BookingStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = meetingTypeId;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = startUtc;
            b.endUtc = startUtc.plusSeconds(1800);
            b.status = status;
            b.createdAt = createdAt;
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private BookingStatus reloadStatus(Long id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> ((Booking) Booking.findById(id)).status);
    }
}
