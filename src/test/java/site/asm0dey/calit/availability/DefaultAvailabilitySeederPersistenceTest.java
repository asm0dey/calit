package site.asm0dey.calit.availability;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

@QuarkusTest
class DefaultAvailabilitySeederPersistenceTest {
    @Transactional
    int seed(Long ownerId) {
        return DefaultAvailabilitySeeder.seedGlobalDefaults(ownerId);
    }

    @Transactional
    long globalCount(Long ownerId) {
        return AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    @Test
    void seedsFiveOwnerStampedWeekdayRules() {
        // admin is always id 1 (DatabaseResetCallback)
        assertThat(seed(1L)).isEqualTo(5);
        assertThat(globalCount(1L)).isEqualTo(5);

        List<AvailabilityRule> monday = AvailabilityRule.globalForOwner(1L, DayOfWeek.MONDAY);
        assertThat(monday).hasSize(1);
        assertThat(monday.getFirst().ownerId).as("every seeded rule must carry the owner id").isOne();
        assertThat(monday.getFirst().startTime).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.getFirst().endTime).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void isIdempotent() {
        assertThat(seed(1L)).isEqualTo(5);
        assertThat(seed(1L)).as("second call must write nothing").isZero();
        assertThat(globalCount(1L)).as("rules must not double").isEqualTo(5);
    }

    @Test
    void doesNothingForNullOwner() {
        assertThat(seed(null)).as("a null owner id must write nothing").isZero();
    }

    @Test
    void doesNotSeedWhenOwnerAlreadyHasGlobalRules() {
        seedOneSaturdayRule(1L);
        assertThat(seed(1L)).isZero();
        assertThat(globalCount(1L)).as("an existing hand-made rule means the owner is not new").isOne();
    }

    @Transactional
    void seedOneSaturdayRule(Long ownerId) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = ownerId;
        r.dayOfWeek = DayOfWeek.SATURDAY;
        r.startTime = LocalTime.of(10, 0);
        r.endTime = LocalTime.of(12, 0);
        r.meetingTypeId = null;
        r.persist();
    }
}
