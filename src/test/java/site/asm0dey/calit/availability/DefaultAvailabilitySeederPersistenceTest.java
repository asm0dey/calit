package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
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
        assertEquals(5, seed(1L)); // admin is always id 1 (DatabaseResetCallback)
        assertEquals(5, globalCount(1L));

        List<AvailabilityRule> monday = AvailabilityRule.globalForOwner(1L, DayOfWeek.MONDAY);
        assertEquals(1, monday.size());
        assertEquals(1L, monday.getFirst().ownerId, "every seeded rule must carry the owner id");
        assertEquals(LocalTime.of(9, 0), monday.getFirst().startTime);
        assertEquals(LocalTime.of(18, 0), monday.getFirst().endTime);
    }

    @Test
    void isIdempotent() {
        assertEquals(5, seed(1L));
        assertEquals(0, seed(1L), "second call must write nothing");
        assertEquals(5, globalCount(1L), "rules must not double");
    }

    @Test
    void doesNotSeedWhenOwnerAlreadyHasGlobalRules() {
        seedOneSaturdayRule(1L);
        assertEquals(0, seed(1L));
        assertEquals(1, globalCount(1L), "an existing hand-made rule means the owner is not new");
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
