package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.privacy.ErasureFixtures;

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
        return QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>findById(id).isErased());
    }

    private static Instant erasedAt(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> Booking.<Booking>findById(id).erasedAt);
    }

    @Test
    void unsetInstanceDefaultIsANoOp() {
        Long id = ErasureFixtures.seedPastBookingId(); // ends 30 days ago
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
}
