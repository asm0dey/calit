package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.privacy.ErasureFixtures;

/**
 * The instance-default half of {@link RetentionSchedulerTest}, split into its own top-level class
 * because {@code @TestProfile} forces its own Quarkus restart — a {@code @Nested} class sharing
 * the enclosing class's boot would never see this config override, and surefire does not run
 * {@code $}-named nested classes as tests in the first place.
 */
@QuarkusTest
@TestProfile(RetentionSchedulerInstanceDefaultTest.WithInstanceDefault.class)
class RetentionSchedulerInstanceDefaultTest {

    @Inject
    RetentionScheduler scheduler;

    private static boolean erased(Long id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> Booking.<Booking>findById(id).isErased());
    }

    @Test
    void instanceDefaultCatchesAnOldBooking() {
        Long id = ErasureFixtures.seedPastBookingId();
        scheduler.sweep();
        assertTrue(erased(id), "a 14-day instance default must catch a booking that ended 30 days ago");
    }

    @Test
    void aLongerOwnerOverrideWins() {
        Long id = ErasureFixtures.seedPastBookingId();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 365;
        });
        scheduler.sweep();
        assertFalse(erased(id), "the owner's own window overrides the instance default in both directions");
    }

    public static class WithInstanceDefault implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.retention.booking-days", "14");
        }
    }
}
