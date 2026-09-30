package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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

    /**
     * Reload from the DB, bypassing the test thread's first-level cache (mutating POST commits in its own tx).
     */
    @Transactional
    AppUser reload(Long id) {
        em.clear();
        return AppUser.findById(id);
    }

    @Test
    @TestSecurity(user = "wiz1", roles = {"user"})
    void getRendersWizardWithPasswordStepWhenForced() {
        seed("wiz1", true);
        given().when().get("/me/setup").then().statusCode(200).body(containsString("New password"));
    }

    @Test
    @TestSecurity(user = "wiz1rtl", roles = {"user"})
    void setupPageIsRtlForHebrew() {
        seedWithLocale("wiz1rtl", true, "he");
        given()
            .when()
            .get("/me/setup")
            .then()
            .statusCode(200)
            .body(containsString("lang=\"he\""))
            .body(containsString("dir=\"rtl\""));
    }

    @Test
    @TestSecurity(user = "wiz1ltr", roles = {"user"})
    void setupPageIsLtrForEnglish() {
        seedWithLocale("wiz1ltr", true, "en");
        given()
            .when()
            .get("/me/setup")
            .then()
            .statusCode(200)
            .body(containsString("lang=\"en\""))
            .body(containsString("dir=\"ltr\""));
    }

    @Test
    @TestSecurity(user = "wizbad", roles = {"user"})
    void postRejectsBlankNameAndInvalidEmail() {
        var id = seed("wizbad", false);
        String[][] bad = {
                {"", "wizbad@example.com", "Enter your name."},
                {"Wiz Bad", "nope", "Enter a valid email address."},
                {null, null, "Enter your name."}
        };
        for (String[] c : bad) {
            var request = given().contentType("application/x-www-form-urlencoded").formParam("timezone", "UTC");
            if (c[0] != null) {
                request.formParam("ownerName", c[0]);
            }
            if (c[1] != null) {
                request.formParam("ownerEmail", c[1]);
            }
            request.when().post("/me/setup").then().statusCode(200).body(containsString(c[2]));
            assertThat(reload(id).settingsComplete).as("an invalid submit must not finish the wizard").isFalse();
        }
    }

    @Test
    @TestSecurity(user = "wiz2", roles = {"user"})
    void postCompletesPasswordAndSettings() {
        var id = seed("wiz2", true);
        given()
            .contentType("application/x-www-form-urlencoded")
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
        assertThat(after.mustChangePassword).isFalse();
        assertThat(after.settingsComplete).isTrue();
        assertThat(HASHER.verify("Brand-new-pw-12345", after.passwordHash)).as("password should have been updated").isTrue();

        OwnerSettings s = OwnerSettings.forOwner(id);
        assertThat(s).isNotNull();
        assertThat(s.ownerName).isEqualTo("Wiz Two");
        assertThat(s.ownerEmail).isEqualTo("wiz2@example.com");
        assertThat(s.timezone).isEqualTo("Europe/Amsterdam");
    }

    @Test
    @TestSecurity(user = "wiz3", roles = {"user"})
    void postSkipsPasswordWhenNotForced() {
        // self-service user: no forced reset
        var id = seed("wiz3", false);
        given()
            .contentType("application/x-www-form-urlencoded")
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
        assertThat(after.settingsComplete).isTrue();
        assertThat(HASHER.verify("Initial-pw-12345", after.passwordHash)).as("password unchanged").isTrue();
    }

    @Test
    @TestSecurity(user = "wiz4", roles = {"user"})
    void notForcedUserCannotChangePasswordViaWizard() {
        var id = seed("wiz4", false);
        // Even if a non-forced user posts a newPassword, the wizard must ignore it (password-change
        // path is structurally gated on mustChangePassword).
        given()
            .contentType("application/x-www-form-urlencoded")
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
        assertThat(after.settingsComplete).isTrue();
        assertThat(HASHER.verify("Initial-pw-12345", after.passwordHash))
            .as("non-forced user's password must be unchanged even when newPassword is supplied")
            .isTrue();
        assertThat(HASHER.verify("Sneaky-new-pw-12345", after.passwordHash)).isFalse();
    }

    @Test
    @TestSecurity(user = "wiz5", roles = {"user"})
    void forcedUserWithBlankPasswordReRendersAndDoesNotComplete() {
        var id = seed("wiz5", true);
        given()
            .contentType("application/x-www-form-urlencoded")
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
        assertThat(after.mustChangePassword).as("still forced — onboarding not advanced").isTrue();
        assertThat(after.settingsComplete).as("settings must not be marked complete on the error path").isFalse();
    }

    /**
     * The wizard is the OTHER path that writes {@code owner_settings.timezone}, and every user
     * passes through it. calit-4whp guarded the settings page but not this one, leaving the same
     * column, the same eleven unguarded {@code ZoneId.of(settings.timezone)} readers -- the owner's
     * PUBLIC booking page and the booking transaction among them -- and the same blast radius open.
     */
    @Test
    @TestSecurity(user = "wiz9", roles = {"user"})
    void wizardCoercesAnUnknownTimezoneToUtc() {
        var id = seed("wiz9", false);
        // The rendered <select> can only submit a real zone id, so this is a hand-crafted POST.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("ownerName", "Wiz Nine")
            .formParam("ownerEmail", "wiz9@example.com")
            .formParam("timezone", "Not/AZone")
            .redirects()
            .follow(false)
            .when()
            .post("/me/setup")
            .then()
            .statusCode(303);

        assertThat(readTimezone(id)).as("an unknown zone id must be coerced, not stored").isEqualTo("UTC");
        // And the owner's own /me pages still render (they call ZoneId.of on this value).
        given().when().get("/me").then().statusCode(200);
    }

    /**
     * Reads {@code timezone} straight from the DB, bypassing the test thread's first-level cache.
     */
    @Transactional
    String readTimezone(Long ownerId) {
        em.clear();
        return OwnerSettings.forOwner(ownerId).timezone;
    }

    @Test
    @TestSecurity(user = "wiz6", roles = {"user"})
    void completingTheWizardSeedsWeekdayDefaults() {
        var id = seed("wiz6", false);
        assertThat(countGlobalRules(id)).as("precondition: a fresh user has no availability").isZero();

        completeWizard();

        assertThat(countGlobalRules(id)).as("Mon–Fri seeded").isEqualTo(5);
        var monday = AvailabilityRule.globalForOwner(id, DayOfWeek.MONDAY);
        assertThat(monday).hasSize(1);
        assertThat(monday.getFirst().startTime).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.getFirst().endTime).isEqualTo(LocalTime.of(18, 0));
        assertThat(monday.getFirst().meetingTypeId).as("defaults are global, not per-type").isNull();
        // The point of the bean: a meeting type made right after onboarding is bookable, with the
        // availability editor never opened.
        MeetingType t = seedMeetingType(id);
        // a Monday
        var monday1 = LocalDate.of(2026, 9, 7);
        assertThat(slotService.generateRawSlots(t, monday1, monday1.plusDays(1)))
            .as("a new user's meeting type must offer slots without touching the availability editor")
            .isNotEmpty();
    }

    @Test
    @TestSecurity(user = "wiz7", roles = {"user"})
    void seedingIsIdempotentAcrossRepeatedWizardSubmits() {
        var id = seed("wiz7", false);
        completeWizard();
        // the wizard is still POST-able; a second submit must not double the rules
        completeWizard();
        assertThat(countGlobalRules(id)).isEqualTo(5);
    }

    @Test
    @TestSecurity(user = "wiz8", roles = {"user"})
    void reSubmittingAfterClearingHoursDoesNotReSeed() {
        var id = seed("wiz8", false);
        completeWizard();
        assertThat(countGlobalRules(id)).as("precondition: wizard seeded the usual defaults").isEqualTo(5);
        // owner deliberately cleared their weekly grid via bulk-save
        clearGlobalRules(id);
        assertThat(countGlobalRules(id)).as("precondition: hours are now empty").isZero();
        // MeOwnerFilter still lets an onboarded user re-POST /me/setup
        completeWizard();

        assertThat(countGlobalRules(id))
            .as("re-submitting an already-onboarded wizard must not re-seed cleared hours")
            .isZero();
    }

    @Transactional
    void clearGlobalRules(Long ownerId) {
        AvailabilityRule.delete("ownerId = ?1 and meetingTypeId is null", ownerId);
    }

    private void completeWizard() {
        given()
            .contentType("application/x-www-form-urlencoded")
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
