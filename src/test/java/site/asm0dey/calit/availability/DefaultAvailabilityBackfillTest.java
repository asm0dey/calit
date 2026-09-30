package site.asm0dey.calit.availability;

import module java.base;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
            assertThat(in).as(MIGRATION + " must be on the test classpath").isNotNull();
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
        // onboarded before the wizard learned to seed
        u.settingsComplete = true;
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
    void backfillsOwnersWithNoGlobalRules() throws Exception {
        var bare = seedUser("legacy1");
        runBackfill();
        assertThat(globalCount(bare)).isEqualTo(5);
        var monday = AvailabilityRule.globalForOwner(bare, DayOfWeek.MONDAY);
        assertThat(monday).hasSize(1);
        assertThat(monday.getFirst().startTime).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.getFirst().endTime).isEqualTo(LocalTime.of(18, 0));
        assertThat(monday.getFirst().meetingTypeId).isNull();
    }

    @Test
    void leavesOwnersWithExistingGlobalRulesAlone() throws Exception {
        var configured = seedUser("legacy2");
        seedOneRule(configured, DayOfWeek.SATURDAY);
        assertThat(configured)
            .as("must differ from the bare admin (id 1) for the assertion below to mean anything")
            .isNotEqualTo(1L);
        runBackfill();
        assertThat(globalCount(configured)).as("hand-set hours must survive untouched").isOne();
        assertThat(AvailabilityRule.globalForOwner(configured, DayOfWeek.MONDAY)).isEmpty();
        // Pins the NOT EXISTS correlation to owner_id: a non-correlated guard (e.g. "skip everyone if
        // ANY availability_rule row exists anywhere") would also make both assertions above pass by
        // seeding nobody at all. Admin (id 1, seeded bare by DatabaseResetCallback) must still be
        // backfilled in this same run to prove the guard is per-owner.
        assertThat(globalCount(1L)).as("another owner with no hours is still backfilled").isEqualTo(5);
    }

    @Test
    void isIdempotent() throws Exception {
        var bare = seedUser("legacy3");
        runBackfill();
        runBackfill();
        assertThat(globalCount(bare)).as("a second run must add nothing").isEqualTo(5);
    }
}
