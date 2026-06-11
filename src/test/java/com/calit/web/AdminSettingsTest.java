package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
        return com.calit.domain.OwnerSettings.forOwner(1L).ownerNotificationsEnabled;
    }

    @Test
    void settingsPageHasNotifyToggleAndReminderLead() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/settings")
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
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("ownerName", "New Owner")
            .formParam("ownerEmail", "new@example.com")
            .formParam("timezone", "Europe/Berlin")
            // ownerNotificationsEnabled intentionally omitted → unchecked → false
            .when().post("/me/settings")
            .then()
                .statusCode(200)
                .body(containsString("New Owner"))
                .body(containsString("Europe/Berlin"));

        org.junit.jupiter.api.Assertions.assertFalse(
                readNotificationsEnabled(),
                "omitting the notify checkbox must turn owner notifications OFF");

        // Now save with it ON and assert it flips back to true.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("ownerName", "New Owner")
            .formParam("ownerEmail", "new@example.com")
            .formParam("timezone", "Europe/Berlin")
            .formParam("ownerNotificationsEnabled", "on")
            .when().post("/me/settings")
            .then().statusCode(200);

        org.junit.jupiter.api.Assertions.assertTrue(
                readNotificationsEnabled());
    }
}
