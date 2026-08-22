package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.MeetingHosts;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 17: co-host add/remove on the meeting-type detail form, plus the create/edit slug guards
 * carried forward from Task 6. Admin (owner id 1) is the fixed test invariant; every scenario
 * starts from a plain single-host type admin owns.
 */
@QuarkusTest
class CohostManageTest {

    @Inject
    BookingService bookingService;

    @Inject
    MeetingHosts meetingHosts;

    @Transactional
    MeetingType seedAdminType(String slug) {
        return MultiHostFixtures.meetingType(1L, slug, 30);
    }

    @Transactional
    AppUser seedCandidate(String username) {
        return MultiHostFixtures.enabledUser(username);
    }

    @Transactional
    void seedOwnType(long ownerId, String slug) {
        MultiHostFixtures.meetingType(ownerId, slug, 30);
    }

    @Transactional
    void seedPendingCohost(Long typeId, Long ownerId) {
        MeetingTypeHost.of(typeId, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost.of(typeId, ownerId, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING)
                .persist();
    }

    @Test
    void addCohostCreatesPendingRowAndListsOnDetailPage() {
        MeetingType t = seedAdminType("cohost-add-" + System.nanoTime());
        AppUser candidate = seedCandidate("candidate-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", candidate.username)
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts")
                .then()
                .statusCode(200)
                .body(containsString(candidate.username));

        MeetingTypeHost host = MeetingTypeHost.find(t.id, candidate.id);
        assertNotNull(host, "cohost row must be created");
        assertEquals(MeetingTypeHost.PENDING, host.status);
        assertEquals(MeetingTypeHost.COHOST, host.role);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + t.id)
                .then()
                .statusCode(200)
                .body(containsString(candidate.username));
    }

    @Test
    void addCohostWithSlugCollisionShowsErrorAndCreatesNoRow() {
        var slug = "collide-" + System.nanoTime();
        AppUser candidate = seedCandidate("collider-" + System.nanoTime());
        seedOwnType(candidate.id, slug); // candidate already owns /candidate/<slug>
        MeetingType t = seedAdminType(slug); // admin's new shared type uses the same slug

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", candidate.username)
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"))
                // localized (i18n) alert text, not the raw hardcoded English string -- Task 17fix
                .body(containsString(candidate.username + " already uses the slug &quot;" + slug + "&quot;"))
                .body(containsString("pick a different slug or ask them to free it"));

        assertNull(MeetingTypeHost.find(t.id, candidate.id), "no cohost row on rejected add");
    }

    @Test
    void addCohostBeyondCapShowsLocalizedCapError() {
        MeetingType t = seedAdminType("cohost-cap-" + System.nanoTime());
        seedCreatorRow(t.id);
        for (var i = 0; i < 9; i++) {
            // MAX_HOSTS is 10: creator + 9 cohosts already fills the type.
            seedAcceptedCohost(t.id, i);
        }
        AppUser extra = seedCandidate("cohost-cap-extra-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", extra.username)
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"))
                // localized (i18n) alert text, not the raw hardcoded English string -- Task 17fix
                .body(containsString("A meeting can have at most 10 hosts."));

        assertNull(MeetingTypeHost.find(t.id, extra.id), "no cohost row on rejected add past the cap");
    }

    @Transactional
    void seedCreatorRow(Long typeId) {
        MeetingTypeHost.of(typeId, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
    }

    @Transactional
    void seedAcceptedCohost(Long typeId, int index) {
        AppUser cohost = MultiHostFixtures.enabledUser("cap-cohost-" + index + "-" + System.nanoTime());
        MeetingTypeHost.of(typeId, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist();
    }

    @Test
    void addCohostWithUnknownUsernameShowsErrorAndCreatesNoRow() {
        MeetingType t = seedAdminType("cohost-unknown-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", "no-such-user-" + System.nanoTime())
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"));

        assertEquals(0, MeetingTypeHost.count("meetingTypeId = ?1 and role = ?2", t.id, MeetingTypeHost.COHOST));
    }

    @Test
    void removeDeletesHostRow() {
        MeetingType t = seedAdminType("cohost-remove-" + System.nanoTime());
        AppUser candidate = seedCandidate("removee-" + System.nanoTime());
        seedPendingCohost(t.id, candidate.id);

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts/" + candidate.id + "/remove")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(t.id, candidate.id), "cohost row removed");
    }

    /**
     * Critical regression (Task 17 review): a direct POST removing the CALLER'S OWN owner id (the
     * CREATOR row) must be rejected server-side, not just hidden client-side. Before the fix this
     * deleted the CREATOR row while an ACCEPTED co-host remained -- {@link
     * MeetingTypeHost#isMultiHost} still reported multi-host (a COHOST row survives), but {@link
     * MeetingHosts#hostOwnerIds} silently dropped the creator, so {@code BookingService.bookGroup}
     * never assigned a lead and NPE'd on every subsequent booking of this type.
     */
    @Test
    void removingCreatorsOwnIdIsRejectedRowSurvivesAndBookingStillWorks() {
        var slug = "creator-guard-" + System.nanoTime();
        AppUser cohost = seedCandidate("cohost-" + System.nanoTime());
        seedBookableSettingsAndRules(1L);
        seedBookableSettingsAndRules(cohost.id);
        MeetingType t = seedAcceptedTwoHostType(1L, cohost.id, slug);
        long rowsBefore = MeetingTypeHost.count("meetingTypeId = ?1", t.id);

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts/1/remove")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"));

        assertNotNull(MeetingTypeHost.find(t.id, 1L), "CREATOR row must survive a self-removal attempt");
        assertEquals(rowsBefore, MeetingTypeHost.count("meetingTypeId = ?1", t.id), "no row was deleted");
        assertTrue(meetingHosts.hostOwnerIds(t).contains(1L), "creator must still be a required host");

        // A subsequent booking on the type must still succeed -- pre-fix this NPE'd in
        // BookingService.persistGuests because bookGroup never assigned a lead.
        Booking lead = bookingService.book(
                1L, t.slug, nextMonday10(), "Sam Invitee", "sam@example.com", Map.of(), "tok", "", "en", List.of());
        assertNotNull(lead);
        assertNotNull(lead.groupId);
    }

    @Test
    void removeOnTypeNotOwnedByCallerReturns404() {
        AppUser otherOwner = seedCandidate("other-owner-" + System.nanoTime());
        MeetingType notMine = seedOwnTypeReturning(otherOwner.id, "not-mine-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + notMine.id + "/hosts/" + otherOwner.id + "/remove")
                .then()
                .statusCode(404);
    }

    @Test
    void addHostOnTypeNotOwnedByCallerReturns404() {
        AppUser otherOwner = seedCandidate("other-owner2-" + System.nanoTime());
        MeetingType notMine = seedOwnTypeReturning(otherOwner.id, "not-mine2-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("cohost", "admin")
                .when()
                .post("/me/meeting-types/" + notMine.id + "/hosts")
                .then()
                .statusCode(404);
    }

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private Instant nextMonday10() {
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(10, 0).atZone(AMS).toInstant();
    }

    @Transactional
    void seedBookableSettingsAndRules(long ownerId) {
        MultiHostFixtures.settings(ownerId, "owner-" + ownerId);
        MultiHostFixtures.rule(ownerId, DayOfWeek.MONDAY, 9, 17);
    }

    @Transactional
    MeetingType seedAcceptedTwoHostType(long creatorId, long cohostId, String slug) {
        return MultiHostFixtures.acceptedTwoHostType(creatorId, cohostId, slug, 30, false);
    }

    @Transactional
    void disableUser(Long ownerId) {
        AppUser u = AppUser.findById(ownerId);
        u.enabled = false;
    }

    /**
     * Pin, not a fix (calit-h8mb): {@link MeetingHosts#addCohost} always ensures a CREATOR row
     * ({@code MeetingHosts.java:196}), and {@link MeetingHosts#bookable} checks {@code u.enabled}
     * for EVERY host row ({@code :63-66}) -- so a multi-host type whose CREATOR was disabled after
     * the co-host accepted was already unbookable via the co-host's alias, before the
     * resolveOwner guard existed. Confirmed to pass unchanged before that fix (task-6 report).
     */
    @Test
    void disabledCreatorMakesCohostAliasUnbookableViaHostPending() {
        var slug = "creator-disabled-" + System.nanoTime();
        AppUser creator = seedCandidate("pin-creator-" + System.nanoTime());
        AppUser cohost = seedCandidate("pin-cohost-" + System.nanoTime());
        seedBookableSettingsAndRules(creator.id);
        seedBookableSettingsAndRules(cohost.id);
        MeetingType t = seedAcceptedTwoHostType(creator.id, cohost.id, slug);
        disableUser(creator.id);

        given().when()
                .get("/" + cohost.username + "/" + slug)
                .then()
                .statusCode(200)
                .body(containsString("CALIT_HOST_PENDING"));
    }

    @Test
    void createRejectsSlugCollidingWithCohostedType() {
        AppUser creator = seedCandidate("other-creator-" + System.nanoTime());
        var slug = "shared-slug-" + System.nanoTime();
        MeetingType shared = seedOwnTypeReturning(creator.id, slug);
        seedAcceptedCohostRow(shared.id, 1L); // admin (id 1) co-hosts creator's type

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Own New Type")
                .formParam("slug", slug) // collides with the type admin already co-hosts
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "+1")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"))
                // localized (i18n) alert text, not the raw hardcoded English string -- Task 17 review fix
                .body(containsString("already co-host a meeting type with the slug"))
                .body(containsString(slug));

        assertNull(MeetingType.findBySlug(1L, slug), "no new own-type created on collision");
    }

    @Test
    void editRejectsRenameCollidingWithCohostedType() {
        AppUser creator = seedCandidate("other-creator2-" + System.nanoTime());
        var collidingSlug = "shared-slug2-" + System.nanoTime();
        MeetingType shared = seedOwnTypeReturning(creator.id, collidingSlug);
        seedAcceptedCohostRow(shared.id, 1L); // admin co-hosts creator's type

        MeetingType own = seedAdminType("own-type-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", own.name)
                .formParam("slug", collidingSlug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "+1")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types/" + own.id + "/edit")
                .then()
                .statusCode(200)
                .body(containsString("alert-error"))
                .body(containsString("already co-host a meeting type with the slug"))
                .body(containsString(collidingSlug));

        MeetingType reloaded = MeetingType.findById(own.id);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                collidingSlug, reloaded.slug, "slug must not be renamed on collision");
    }

    @Transactional
    MeetingType seedOwnTypeReturning(long ownerId, String slug) {
        return MultiHostFixtures.meetingType(ownerId, slug, 30);
    }

    @Transactional
    void seedAcceptedCohostRow(Long typeId, Long ownerId) {
        MeetingTypeHost.of(typeId, ownerId, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist();
    }

    /**
     * Bug fix regression: a plain single-host type holds NO {@link MeetingTypeHost} rows at all, so
     * the Hosts tokenfield used to render completely empty for it. The owner must always appear as
     * a persistent, non-removable "Creator" chip -- synthesized on the fly when no real CREATOR row
     * exists.
     */
    @Test
    void singleHostTypeDetailPageShowsOwnerAsCreatorChipWithNoHostRows() {
        MeetingType t = seedAdminType("single-host-" + System.nanoTime());
        assertEquals(0, MeetingTypeHost.count("meetingTypeId = ?1", t.id), "single-host type has no host rows");

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + t.id)
                .then()
                .statusCode(200)
                .body(containsString("admin"))
                .body(containsString("Creator"));
    }

    /**
     * After removing the last co-host, {@link MeetingHosts} deletes the CREATOR row too (revert to
     * single-host, by design). The detail page must still show the owner's chip -- not an empty
     * Hosts control.
     */
    @Test
    void removingLastCohostLeavesCreatorChipVisible() {
        MeetingType t = seedAdminType("last-cohost-" + System.nanoTime());
        AppUser candidate = seedCandidate("last-cohost-cand-" + System.nanoTime());
        seedPendingCohost(t.id, candidate.id);

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + t.id + "/hosts/" + candidate.id + "/remove")
                .then()
                .statusCode(200);

        assertEquals(
                0,
                MeetingTypeHost.count("meetingTypeId = ?1", t.id),
                "removing the last co-host also removes the CREATOR row (revert to single-host)");

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + t.id)
                .then()
                .statusCode(200)
                .body(containsString("admin"))
                .body(containsString("Creator"));
    }
}
