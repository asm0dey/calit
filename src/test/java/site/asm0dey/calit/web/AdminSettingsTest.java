package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AdminSettingsTest {

    @Inject
    EntityManager em;

    /**
     * Reads {@code ownerNotificationsEnabled} straight from the DB, bypassing the test thread's
     * first-level cache. The POST commits in its own request transaction; a plain
     * {@code OwnerSettings.forOwner(1L)} here would return the stale entity cached by an earlier read in
     * the same non-transactional test method, so we clear the context and re-query.
     */
    @Transactional
    boolean readNotificationsEnabled() {
        em.clear();
        return site.asm0dey.calit.domain.OwnerSettings.forOwner(1L).ownerNotificationsEnabled;
    }

    @Test
    void settingsPageHasNotifyToggleAndReminderLead() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                // Owner-notify opt-out toggle (overview: OwnerSettings.ownerNotificationsEnabled).
                .body(containsString("name=\"ownerNotificationsEnabled\""))
                // Reminder lead-time (config-backed, feature 15) shown as a read-only value.
                .body(containsString("Reminder lead"));
    }

    @Test
    void updateSettingsTogglesOwnerNotifications() {
        // Save with the notify checkbox OFF (omitted → unchecked) and assert it persists false.
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "New Owner")
                .formParam("ownerEmail", "new@example.com")
                .formParam("timezone", "Europe/Berlin")
                // ownerNotificationsEnabled intentionally omitted → unchecked → false
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200)
                .body(containsString("New Owner"))
                .body(containsString("Europe/Berlin"));

        org.junit.jupiter.api.Assertions.assertFalse(
                readNotificationsEnabled(), "omitting the notify checkbox must turn owner notifications OFF");

        // Now save with it ON and assert it flips back to true.
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "New Owner")
                .formParam("ownerEmail", "new@example.com")
                .formParam("timezone", "Europe/Berlin")
                .formParam("ownerNotificationsEnabled", "on")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        org.junit.jupiter.api.Assertions.assertTrue(readNotificationsEnabled());
    }

    /** Reads {@code timezone} straight from the DB, bypassing the test thread's first-level cache. */
    @Transactional
    String readTimezone() {
        em.clear();
        return site.asm0dey.calit.domain.OwnerSettings.forOwner(1L).timezone;
    }

    @Test
    void updateSettingsCoercesAnUnknownTimezoneToUtc() {
        // The <select> can only submit a real zone id, so this is a hand-crafted POST. It must not
        // be able to park a DateTimeException in the DB: eleven unguarded ZoneId.of(...) call sites
        // read this column, including the owner's PUBLIC booking page and the booking transaction.
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "New Owner")
                .formParam("ownerEmail", "new@example.com")
                .formParam("timezone", "Not/AZone")
                .formParam("locale", "en")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals(
                "UTC", readTimezone(), "an unknown zone id must be coerced, not stored");

        // And the owner's own /me pages still render (they call ZoneId.of on this value).
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200);
    }

    /** Reads {@code bookingRetentionDays} straight from the DB, bypassing the test thread's first-level cache. */
    @Transactional
    Integer readRetentionDays() {
        em.clear();
        return site.asm0dey.calit.domain.OwnerSettings.forOwner(1L).bookingRetentionDays;
    }

    /**
     * Blank/zero/negative/unparseable all mean "no override" (null); an in-range value is stored
     * as-is; anything above {@code PrivacyConfig.MAX_RETENTION_DAYS} (36500) is clamped down to it
     * rather than rejected (R20) — see {@code AdminResource.parseRetentionDays}. The last case also
     * confirms the settings page pre-fills the saved value.
     */
    @Test
    void updateSettingsParsesAndClampsRetentionDays() {
        assertRetentionStores("", null);
        assertRetentionStores("0", null);
        assertRetentionStores("-3", null);
        assertRetentionStores("abc", null);
        assertRetentionStores("99999999", 36500);
        assertRetentionStores("30", 30);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .body(containsString("name=\"bookingRetentionDays\" value=\"30\""));
    }

    /** POSTs the full settings form (every field {@code updateSettings} reads) with one retention value. */
    private void assertRetentionStores(String posted, Integer expected) {
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "New Owner")
                .formParam("ownerEmail", "new@example.com")
                .formParam("timezone", "Europe/Berlin")
                .formParam("locale", "en")
                .formParam("timeFormat", "auto")
                .formParam("ownerNotificationsEnabled", "on")
                .formParam("bookingRetentionDays", posted)
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals(
                expected, readRetentionDays(), "posting bookingRetentionDays=\"" + posted + "\"");
    }
}
