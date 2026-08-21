package site.asm0dey.calit.availability;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.user.AppUser;

/**
 * The backfill runs at boot against a database the test callback then truncates, so its effect can
 * never be observed in situ. Instead this executes the migration's OWN sql text against a seeded
 * database — the statement is read from the classpath, so the test cannot drift from the migration.
 */
@QuarkusTest
class DefaultAvailabilityBackfillTest {

    private static final String MIGRATION = "/db/migration/V28__seed_default_availability.sql";

    @Inject
    EntityManager em;

    private String migrationSql() throws IOException {
        try (var in = getClass().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, MIGRATION + " must be on the test classpath");
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    @Transactional
    int runBackfill() throws IOException {
        return em.createNativeQuery(migrationSql()).executeUpdate();
    }

    @Transactional
    Long seedUser(String username) {
        AppUser u = AppUser.create(username, null, false);
        u.settingsComplete = true; // onboarded before the wizard learned to seed
        u.persist();
        return u.id;
    }

    @Transactional
    void seedOneRule(Long ownerId, DayOfWeek day) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = ownerId;
        r.dayOfWeek = day;
        r.startTime = LocalTime.of(10, 0);
        r.endTime = LocalTime.of(12, 0);
        r.meetingTypeId = null;
        r.persist();
    }

    @Transactional
    long globalCount(Long ownerId) {
        em.clear();
        return AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    @Test
    void backfillsOwnersWithNoGlobalRules() throws IOException {
        var bare = seedUser("legacy1");
        runBackfill();
        assertEquals(5, globalCount(bare));
        var monday = AvailabilityRule.globalForOwner(bare, DayOfWeek.MONDAY);
        assertEquals(1, monday.size());
        assertEquals(LocalTime.of(9, 0), monday.getFirst().startTime);
        assertEquals(LocalTime.of(18, 0), monday.getFirst().endTime);
        assertNull(monday.getFirst().meetingTypeId);
    }

    @Test
    void leavesOwnersWithExistingGlobalRulesAlone() throws IOException {
        var configured = seedUser("legacy2");
        seedOneRule(configured, DayOfWeek.SATURDAY);
        runBackfill();
        assertEquals(1, globalCount(configured), "hand-set hours must survive untouched");
        assertTrue(AvailabilityRule.globalForOwner(configured, DayOfWeek.MONDAY).isEmpty());
    }

    @Test
    void isIdempotent() throws IOException {
        var bare = seedUser("legacy3");
        runBackfill();
        runBackfill();
        assertEquals(5, globalCount(bare), "a second run must add nothing");
    }
}
