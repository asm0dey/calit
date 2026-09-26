package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.privacy.ErasureFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Default config: {@code calit.retention.booking-days} unset. The instance-default case lives in
 * the top-level {@link RetentionSchedulerInstanceDefaultTest} — a {@code @Nested @TestProfile}
 * class here would never run: surefire skips {@code $}-named inner classes and a
 * {@code @TestProfile} triggers its own Quarkus restart, neither of which a nested class gets.
 */
@QuarkusTest
class RetentionSchedulerTest {
    @Inject
    RetentionScheduler scheduler;

    private static boolean erased(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>findById(id).isErased());
    }

    private static Instant erasedAt(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>findById(id).erasedAt);
    }

    @Test
    void unsetInstanceDefaultIsANoOp() {
        // ends 30 days ago
        Long id = ErasureFixtures.seedPastBookingId();
        scheduler.sweep();
        assertFalse(erased(id), "an unset retention window must keep bookings forever");
    }

    @Test
    void perOwnerOverrideAppliesWithoutAnInstanceDefault() {
        Long id = ErasureFixtures.seedPastBookingId();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 7;
        });
        scheduler.sweep();
        assertTrue(erased(id), "a 7-day owner window must catch a booking that ended 30 days ago");
    }

    @Test
    void aFutureBookingIsNeverTouched() {
        Long id = ErasureFixtures.seedUpcomingBookingId();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 1;
        });
        scheduler.sweep();
        assertFalse(erased(id), "retention measures from end_utc; a future booking has not ended");
    }

    /**
     * The erasure SQL filters {@code erased_at IS NULL}, so an already-erased row is never
     * reselected: a second sweep must leave its {@code erased_at} stamp exactly as the first
     * sweep set it, not refresh it to a later time.
     */
    @Test
    void aSecondSweepLeavesAnAlreadyErasedBookingUnchanged() {
        Long id = ErasureFixtures.seedPastBookingId();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 7;
        });
        scheduler.sweep();
        assertTrue(erased(id), "precondition: the first sweep must have erased the booking");
        var firstStamp = erasedAt(id);

        scheduler.sweep();

        assertEquals(firstStamp, erasedAt(id), "a second sweep must not touch an already-erased booking");
    }

    /**
     * A backlog larger than one batch drains in ONE sweep: the tick keeps claiming batches until one
     * comes back short. Batch size 2 against 5 rows forces three batches (2, 2, 1) without seeding
     * hundreds of bookings.
     */
    @Test
    void oneSweepDrainsMoreThanOneBatch() {
        var ids = java.util.stream.IntStream
            .range(0, 5)
            .mapToObj(i -> ErasureFixtures.seedPastBookingId())
            .toList();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 7;
        });

        assertEquals(5, scheduler.sweep(2), "every row past its window is anonymised in one tick");

        ids.forEach(id -> assertTrue(erased(id), "booking " + id + " must be erased"));
    }

    /**
     * R20: an unclamped huge window makes Postgres' {@code make_interval}/{@code timestamp}
     * arithmetic raise "timestamp out of range" — and since one sweep tick is one transaction, that
     * would stop retention for every owner that tick. The scheduler's SQL clamps via
     * {@code LEAST(..., :cap)}, so this must not throw, and — clamped to ~100 years — a 30-day-old
     * booking stays well inside the window and is not erased.
     */
    @Test
    void aHugeOwnerRetentionValueIsClampedAndDoesNotThrow() {
        Long id = ErasureFixtures.seedPastBookingId();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 99_999_999;
        });

        assertDoesNotThrow(() -> scheduler.sweep(), "a huge retention window must not blow up interval arithmetic");

        assertFalse(erased(id), "clamped to ~100 years, a 30-day-old booking is still well inside the window");
    }

    /**
     * A booking whose window has not yet elapsed must be kept — the mirror of the 7-day-catches case.
     */
    @Test
    void aBookingStillInsideItsWindowIsKept() {
        // ends ~30 days ago
        Long id = ErasureFixtures.seedPastBookingId();
        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                // window longer than the booking's age
                s.bookingRetentionDays = 60;
            });
        scheduler.sweep();
        assertFalse(erased(id), "a 60-day window must keep a booking that ended only 30 days ago");
    }

    /**
     * Each row is measured against ITS OWN owner's window — one sweep must erase only the owner
     * whose window has elapsed, never the other, even though both bookings ended around the same
     * time.
     */
    @Test
    void twoOwnersWithDifferentWindowsOnlyErasesTheOnePastItsOwnWindow() {
        // owner 1, ends ~30 days ago
        Long ownerOneBookingId = ErasureFixtures.seedPastBookingId();
        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                OwnerSettings s = OwnerSettings.forOwner(1L);
                // shorter than 30 -> must be erased
                s.bookingRetentionDays = 7;
            });

        Long ownerTwoBookingId = QuarkusTransaction
            .requiringNew()
            .call(() -> {
                var u = new AppUser();
                u.username = "retention-owner-two";
                u.passwordHash = "x";
                u.roles = "user";
                u.enabled = true;
                u.isAdmin = false;
                u.createdAt = Instant.now();
                u.persist();

                var s = new OwnerSettings();
                s.ownerId = u.id;
                s.ownerName = "Owner Two";
                s.ownerEmail = "owner-two@example.com";
                s.timezone = "UTC";
                // much longer -> must be kept
                s.bookingRetentionDays = 365;
                s.persist();

                var t = new MeetingType();
                t.ownerId = u.id;
                t.name = "Owner Two's type";
                t.slug = "retention-owner-two-type";
                t.durationMinutes = 30;
                t.persist();

                var b = new Booking();
                b.ownerId = u.id;
                b.meetingTypeId = t.id;
                b.inviteeName = "Other Invitee";
                b.inviteeEmail = "other@example.com";
                b.startUtc = Instant.now().minus(30, ChronoUnit.DAYS);
                b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.CONFIRMED;
                b.createdAt = Instant.now().minus(31, ChronoUnit.DAYS);
                b.manageToken = UUID.randomUUID().toString();
                b.persist();
                return b.id;
            });

        scheduler.sweep();

        assertTrue(erased(ownerOneBookingId), "owner 1's 7-day window must catch its 30-day-old booking");
        assertFalse(erased(ownerTwoBookingId), "owner 2's 365-day window must keep its 30-day-old booking");
    }
}
