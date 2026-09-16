package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.privacy.ErasureFixtures;
import site.asm0dey.calit.user.AppUser;

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

    /**
     * The other direction of {@link #aLongerOwnerOverrideWins}: a booking too young for the 14-day
     * instance default (10 days old) must still be erased when the owner's OWN window is shorter (5
     * days) — proving the override actually took effect, not merely that the default alone would
     * have caught it.
     */
    @Test
    void aShorterOwnerOverrideCatchesWhatTheInstanceDefaultWouldMiss() {
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            var b = new Booking();
            b.ownerId = ErasureFixtures.OWNER;
            b.meetingTypeId = ErasureFixtures.firstMeetingTypeId();
            b.inviteeName = "Dana Vogel";
            b.inviteeEmail = "dana@example.com";
            b.startUtc = Instant.now().minus(10, ChronoUnit.DAYS);
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now().minus(10, ChronoUnit.DAYS);
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            s.bookingRetentionDays = 5; // shorter than the 14-day instance default
        });

        scheduler.sweep();

        assertTrue(
                erased(id),
                "a 5-day owner window (shorter than the 14-day instance default) must catch a "
                        + "10-day-old booking the default alone would miss");
    }

    /**
     * R22: the sweep's SQL uses a LEFT JOIN so a booking whose owner has no {@code owner_settings}
     * row at all (never actually reachable through any real account-creation path, but the join must
     * not silently exclude it) still falls back to the instance default rather than being skipped.
     */
    @Test
    void anOwnerWithNoSettingsRowStillGetsTheInstanceDefault() {
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            var u = new AppUser();
            u.username = "retention-no-settings";
            u.passwordHash = "x";
            u.roles = "user";
            u.enabled = true;
            u.isAdmin = false;
            u.createdAt = Instant.now();
            u.persist();
            // Deliberately no OwnerSettings row for this owner.

            var t = new MeetingType();
            t.ownerId = u.id;
            t.name = "No settings row type";
            t.slug = "retention-no-settings-type";
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

        assertTrue(erased(id), "an owner with no owner_settings row must still fall back to the instance default");
    }

    public static class WithInstanceDefault implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.retention.booking-days", "14");
        }
    }
}
