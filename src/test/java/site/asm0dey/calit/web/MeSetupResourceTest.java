package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;

@QuarkusTest
class MeSetupResourceTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Inject
    EntityManager em;

    @Inject
    SlotService slotService;

    @Transactional
    Long seed(String username, boolean mustChange) {
        return seedWithLocale(username, mustChange, "en");
    }

    @Transactional
    Long seedWithLocale(String username, boolean mustChange, String locale) {
        AppUser u = AppUser.create(username, HASHER.hash("Initial-pw-12345"), false);
        u.mustChangePassword = mustChange;
        u.settingsComplete = false;
        u.persist();
        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.ownerName = username;
        s.ownerEmail = username + "@example.com";
        s.timezone = "UTC";
        s.locale = locale;
        s.persist();
        return u.id;
    }

    /** Reload from the DB, bypassing the test thread's first-level cache (mutating POST commits in its own tx). */
    @Transactional
    AppUser reload(Long id) {
        em.clear();
        return AppUser.findById(id);
    }

    @Test
    @TestSecurity(
            user = "wiz1",
            roles = {"user"})
    void getRendersWizardWithPasswordStepWhenForced() {
        seed("wiz1", true);
        given().when().get("/me/setup").then().statusCode(200).body(containsString("New password"));
    }

    @Test
    @TestSecurity(
            user = "wiz1rtl",
            roles = {"user"})
    void setupPageIsRtlForHebrew() {
        seedWithLocale("wiz1rtl", true, "he");
        given().when()
                .get("/me/setup")
                .then()
                .statusCode(200)
                .body(containsString("lang=\"he\""))
                .body(containsString("dir=\"rtl\""));
    }

    @Test
    @TestSecurity(
            user = "wiz1ltr",
            roles = {"user"})
    void setupPageIsLtrForEnglish() {
        seedWithLocale("wiz1ltr", true, "en");
        given().when()
                .get("/me/setup")
                .then()
                .statusCode(200)
                .body(containsString("lang=\"en\""))
                .body(containsString("dir=\"ltr\""));
    }

    @Test
    @TestSecurity(
            user = "wiz2",
            roles = {"user"})
    void postCompletesPasswordAndSettings() {
        var id = seed("wiz2", true);
        given().contentType("application/x-www-form-urlencoded")
                .formParam("newPassword", "Brand-new-pw-12345")
                .formParam("ownerName", "Wiz Two")
                .formParam("ownerEmail", "wiz2@example.com")
                .formParam("timezone", "Europe/Amsterdam")
                .redirects()
                .follow(false)
                .when()
                .post("/me/setup")
                .then()
                .statusCode(303);

        AppUser after = reload(id);
        assertFalse(after.mustChangePassword);
        assertTrue(after.settingsComplete);
        assertTrue(HASHER.verify("Brand-new-pw-12345", after.passwordHash), "password should have been updated");

        OwnerSettings s = OwnerSettings.forOwner(id);
        assertNotNull(s);
        assertEquals("Wiz Two", s.ownerName);
        assertEquals("wiz2@example.com", s.ownerEmail);
        assertEquals("Europe/Amsterdam", s.timezone);
    }

    @Test
    @TestSecurity(
            user = "wiz3",
            roles = {"user"})
    void postSkipsPasswordWhenNotForced() {
        var id = seed("wiz3", false); // self-service user: no forced reset
        given().contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "Wiz Three")
                .formParam("ownerEmail", "wiz3@example.com")
                .formParam("timezone", "Europe/Amsterdam")
                .redirects()
                .follow(false)
                .when()
                .post("/me/setup")
                .then()
                .statusCode(303);

        AppUser after = reload(id);
        assertTrue(after.settingsComplete);
        assertTrue(HASHER.verify("Initial-pw-12345", after.passwordHash), "password unchanged");
    }

    @Test
    @TestSecurity(
            user = "wiz4",
            roles = {"user"})
    void notForcedUserCannotChangePasswordViaWizard() {
        var id = seed("wiz4", false);
        // Even if a non-forced user posts a newPassword, the wizard must ignore it (password-change
        // path is structurally gated on mustChangePassword).
        given().contentType("application/x-www-form-urlencoded")
                .formParam("newPassword", "Sneaky-new-pw-12345")
                .formParam("ownerName", "Wiz Four")
                .formParam("ownerEmail", "wiz4@example.com")
                .formParam("timezone", "Europe/Amsterdam")
                .redirects()
                .follow(false)
                .when()
                .post("/me/setup")
                .then()
                .statusCode(303);

        AppUser after = reload(id);
        assertTrue(after.settingsComplete);
        assertTrue(
                HASHER.verify("Initial-pw-12345", after.passwordHash),
                "non-forced user's password must be unchanged even when newPassword is supplied");
        assertFalse(HASHER.verify("Sneaky-new-pw-12345", after.passwordHash));
    }

    @Test
    @TestSecurity(
            user = "wiz5",
            roles = {"user"})
    void forcedUserWithBlankPasswordReRendersAndDoesNotComplete() {
        var id = seed("wiz5", true);
        given().contentType("application/x-www-form-urlencoded")
                // newPassword omitted (blank) while mustChangePassword is set.
                .formParam("ownerName", "Wiz Five")
                .formParam("ownerEmail", "wiz5@example.com")
                .formParam("timezone", "Europe/Amsterdam")
                .when()
                .post("/me/setup")
                .then()
                .statusCode(200)
                .body(containsString("Please choose a new password"));

        AppUser after = reload(id);
        assertTrue(after.mustChangePassword, "still forced — onboarding not advanced");
        assertFalse(after.settingsComplete, "settings must not be marked complete on the error path");
    }

    /**
     * The wizard is the OTHER path that writes {@code owner_settings.timezone}, and every user
     * passes through it. calit-4whp guarded the settings page but not this one, leaving the same
     * column, the same eleven unguarded {@code ZoneId.of(settings.timezone)} readers -- the owner's
     * PUBLIC booking page and the booking transaction among them -- and the same blast radius open.
     */
    @Test
    @TestSecurity(
            user = "wiz9",
            roles = {"user"})
    void wizardCoercesAnUnknownTimezoneToUtc() {
        var id = seed("wiz9", false);
        // The rendered <select> can only submit a real zone id, so this is a hand-crafted POST.
        given().contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "Wiz Nine")
                .formParam("ownerEmail", "wiz9@example.com")
                .formParam("timezone", "Not/AZone")
                .redirects()
                .follow(false)
                .when()
                .post("/me/setup")
                .then()
                .statusCode(303);

        assertEquals("UTC", readTimezone(id), "an unknown zone id must be coerced, not stored");

        // And the owner's own /me pages still render (they call ZoneId.of on this value).
        given().when().get("/me").then().statusCode(200);
    }

    /** Reads {@code timezone} straight from the DB, bypassing the test thread's first-level cache. */
    @Transactional
    String readTimezone(Long ownerId) {
        em.clear();
        return OwnerSettings.forOwner(ownerId).timezone;
    }

    @Test
    @TestSecurity(
            user = "wiz6",
            roles = {"user"})
    void completingTheWizardSeedsWeekdayDefaults() {
        var id = seed("wiz6", false);
        assertEquals(0, countGlobalRules(id), "precondition: a fresh user has no availability");

        completeWizard();

        assertEquals(5, countGlobalRules(id), "Mon–Fri seeded");
        var monday = AvailabilityRule.globalForOwner(id, DayOfWeek.MONDAY);
        assertEquals(1, monday.size());
        assertEquals(LocalTime.of(9, 0), monday.getFirst().startTime);
        assertEquals(LocalTime.of(18, 0), monday.getFirst().endTime);
        assertNull(monday.getFirst().meetingTypeId, "defaults are global, not per-type");

        // The point of the bean: a meeting type made right after onboarding is bookable, with the
        // availability editor never opened.
        MeetingType t = seedMeetingType(id);
        var monday1 = LocalDate.of(2026, 9, 7); // a Monday
        assertFalse(
                slotService.generateRawSlots(t, monday1, monday1.plusDays(1)).isEmpty(),
                "a new user's meeting type must offer slots without touching the availability editor");
    }

    @Test
    @TestSecurity(
            user = "wiz7",
            roles = {"user"})
    void seedingIsIdempotentAcrossRepeatedWizardSubmits() {
        var id = seed("wiz7", false);
        completeWizard();
        completeWizard(); // the wizard is still POST-able; a second submit must not double the rules
        assertEquals(5, countGlobalRules(id));
    }

    @Test
    @TestSecurity(
            user = "wiz8",
            roles = {"user"})
    void reSubmittingAfterClearingHoursDoesNotReSeed() {
        var id = seed("wiz8", false);
        completeWizard();
        assertEquals(5, countGlobalRules(id), "precondition: wizard seeded the usual defaults");

        clearGlobalRules(id); // owner deliberately cleared their weekly grid via bulk-save
        assertEquals(0, countGlobalRules(id), "precondition: hours are now empty");

        completeWizard(); // MeOwnerFilter still lets an onboarded user re-POST /me/setup

        assertEquals(
                0, countGlobalRules(id), "re-submitting an already-onboarded wizard must not re-seed cleared hours");
    }

    @Transactional
    void clearGlobalRules(Long ownerId) {
        AvailabilityRule.delete("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    private void completeWizard() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "Wiz")
                .formParam("ownerEmail", "wiz@example.com")
                .formParam("timezone", "Europe/Amsterdam")
                .redirects()
                .follow(false)
                .when()
                .post("/me/setup")
                .then()
                .statusCode(303);
    }

    @Transactional
    long countGlobalRules(Long ownerId) {
        em.clear();
        return AvailabilityRule.count("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    @Transactional
    MeetingType seedMeetingType(Long ownerId) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Intro";
        t.slug = "intro";
        t.durationMinutes = 30;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        return t;
    }
}
