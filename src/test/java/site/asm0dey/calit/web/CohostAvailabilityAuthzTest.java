package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.DateOverride;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 19: authorization on the co-host's shared-type availability editor
 * ({@code /me/shared/{typeId}/availability...}). Owner-scoped invariant (CLAUDE.md): a co-host
 * may write availability for a type ONLY if ACCEPTED on that type, and ONLY under their own
 * owner_id — never another host's rows, never a non-host's.
 */
@QuarkusTest
class CohostAvailabilityAuthzTest {
    @Transactional
    AppUser seedUser(String username) {
        return MultiHostFixtures.enabledUser(username);
    }

    @Transactional
    MeetingType seedTwoHostType(long creatorId, long cohostId, String slug) {
        return MultiHostFixtures.acceptedTwoHostType(creatorId, cohostId, slug, 30, false);
    }

    @Transactional
    MeetingType seedThreeHostType(long creatorId, long cohostAId, long cohostBId, String slug) {
        MeetingType t = MultiHostFixtures.meetingType(creatorId, slug, 30);
        t.horizonDays = 50000;
        t.persist();
        MeetingTypeHost.of(t.id, creatorId, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, cohostAId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, cohostBId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        return t;
    }

    @Transactional
    AvailabilityRule seedRule(long ownerId, Long typeId, DayOfWeek day, int startHour, int endHour) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = ownerId;
        r.meetingTypeId = typeId;
        r.dayOfWeek = day;
        r.startTime = LocalTime.of(startHour, 0);
        r.endTime = LocalTime.of(endHour, 0);
        r.persist();
        return r;
    }

    @Test
    @TestSecurity(user = "cohost-a", roles = {"user"})
    void acceptedCohostCanWriteAvailabilityForSharedType() {
        AppUser cohost = seedUser("cohost-a");
        MeetingType t = seedTwoHostType(1L, cohost.id, "authz-write-" + System.nanoTime());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("frameDay", "MONDAY")
            .formParam("frameStart", "09:00")
            .formParam("frameEnd", "17:00")
            .when()
            .post("/me/shared/" + t.id + "/availability/bulk")
            .then()
            .statusCode(200);

        List<AvailabilityRule> rules = AvailabilityRule.list("ownerId = ?1 and meetingTypeId = ?2", cohost.id, t.id);
        assertThat(rules).as("rule must be persisted under the cohost's own owner_id + typeId").hasSize(1);
        assertThat(rules.get(0).dayOfWeek).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @TestSecurity(user = "stranger", roles = {"user"})
    void nonHostGetsGet404() {
        seedUser("stranger");
        AppUser otherCohost = seedUser("other-cohost-" + System.nanoTime());
        MeetingType t = seedTwoHostType(1L, otherCohost.id, "authz-nonhost-get-" + System.nanoTime());

        given().when().get("/me/shared/" + t.id + "/availability").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "stranger2", roles = {"user"})
    void nonHostGetsPost404AndCreatesNoRows() {
        seedUser("stranger2");
        AppUser otherCohost = seedUser("other-cohost2-" + System.nanoTime());
        MeetingType t = seedTwoHostType(1L, otherCohost.id, "authz-nonhost-post-" + System.nanoTime());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("frameDay", "MONDAY")
            .formParam("frameStart", "09:00")
            .formParam("frameEnd", "17:00")
            .when()
            .post("/me/shared/" + t.id + "/availability/bulk")
            .then()
            .statusCode(404);

        assertThat(AvailabilityRule.count("meetingTypeId = ?1", t.id))
            .as("a non-host POST must not create any availability rows")
            .isZero();
    }

    @Test
    @TestSecurity(user = "cohost-b", roles = {"user"})
    void hostCannotOverwriteAnotherHostsRows() {
        AppUser cohostA = seedUser("cohost-a-" + System.nanoTime());
        AppUser cohostB = seedUser("cohost-b");
        MeetingType t = seedThreeHostType(1L, cohostA.id, cohostB.id, "authz-scoped-" + System.nanoTime());
        // cohostA's own pre-existing schedule
        seedRule(cohostA.id, t.id, DayOfWeek.TUESDAY, 8, 12);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("frameDay", "WEDNESDAY")
            .formParam("frameStart", "10:00")
            .formParam("frameEnd", "14:00")
            .when()
            .post("/me/shared/" + t.id + "/availability/bulk")
            .then()
            .statusCode(200);

        List<AvailabilityRule> aRules = AvailabilityRule.list("ownerId = ?1 and meetingTypeId = ?2", cohostA.id, t.id);
        assertThat(aRules).as("cohost B's write must not touch cohost A's rows").hasSize(1);
        assertThat(aRules.get(0).dayOfWeek).isEqualTo(DayOfWeek.TUESDAY);

        List<AvailabilityRule> bRules = AvailabilityRule.list("ownerId = ?1 and meetingTypeId = ?2", cohostB.id, t.id);
        assertThat(bRules).as("cohost B's own row must be written under their own owner_id").hasSize(1);
        assertThat(bRules.get(0).dayOfWeek).isEqualTo(DayOfWeek.WEDNESDAY);
    }

    // ---- Task 19-fix: crafted/garbage input must never 500 ----
    @Test
    @TestSecurity(user = "cohost-buf-nonnumeric", roles = {"user"})
    void nonNumericBufferIsTreatedAsUnsetNot500() {
        AppUser cohost = seedUser("cohost-buf-nonnumeric");
        MeetingType t = seedTwoHostType(1L, cohost.id, "authz-buf-nonnum-" + System.nanoTime());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("bufferBeforeMinutes", "not-a-number")
            .formParam("bufferAfterMinutes", "also-bogus")
            .when()
            .post("/me/shared/" + t.id + "/buffers")
            .then()
            .statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(t.id, cohost.id);
        assertThat(h.bufferBeforeMinutes).as("non-numeric buffer must be treated as unset (inherit default)").isNull();
        assertThat(h.bufferAfterMinutes).as("non-numeric buffer must be treated as unset (inherit default)").isNull();
    }

    @Test
    @TestSecurity(user = "cohost-buf-negative", roles = {"user"})
    void negativeBufferIsClampedToZeroNot500() {
        AppUser cohost = seedUser("cohost-buf-negative");
        MeetingType t = seedTwoHostType(1L, cohost.id, "authz-buf-neg-" + System.nanoTime());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("bufferBeforeMinutes", "-15")
            .formParam("bufferAfterMinutes", "10")
            .when()
            .post("/me/shared/" + t.id + "/buffers")
            .then()
            .statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(t.id, cohost.id);
        assertThat(h.bufferBeforeMinutes).as("negative buffer must be clamped to 0, never persisted as negative").isZero();
        assertThat(h.bufferAfterMinutes).isEqualTo(10);
    }

    @Test
    @TestSecurity(user = "cohost-frame-bad", roles = {"user"})
    void malformedFrameTimeIsSkippedValidFramesStillSaved() {
        AppUser cohost = seedUser("cohost-frame-bad");
        MeetingType t = seedTwoHostType(1L, cohost.id, "authz-frame-bad-" + System.nanoTime());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("frameDay", "MONDAY", "TUESDAY")
            .formParam("frameStart", "not-a-time", "09:00")
            .formParam("frameEnd", "17:00", "17:00")
            .when()
            .post("/me/shared/" + t.id + "/availability/bulk")
            .then()
            .statusCode(200);

        List<AvailabilityRule> rules = AvailabilityRule.list("ownerId = ?1 and meetingTypeId = ?2", cohost.id, t.id);
        assertThat(rules).as("the malformed frame must be skipped, the valid one persisted").hasSize(1);
        assertThat(rules.get(0).dayOfWeek).isEqualTo(DayOfWeek.TUESDAY);
    }

    @Test
    @TestSecurity(user = "cohost-date-bad", roles = {"user"})
    void malformedOverrideDateIsSkippedNot500() {
        AppUser cohost = seedUser("cohost-date-bad");
        MeetingType t = seedTwoHostType(1L, cohost.id, "authz-date-bad-" + System.nanoTime());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "not-a-date")
            .formParam("windowStart", "09:00")
            .formParam("windowEnd", "17:00")
            .when()
            .post("/me/shared/" + t.id + "/date-overrides")
            .then()
            .statusCode(200);

        assertThat(DateOverride.count("ownerId = ?1 and meetingTypeId = ?2", cohost.id, t.id))
            .as("a malformed override date must not be persisted")
            .isZero();
    }
}
