package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminMeetingTypesTest {

    @Transactional
    void seedSecret() {
        MeetingType secret = new MeetingType();
        secret.ownerId = 1L;
        secret.name = "Admin Visible Secret"; secret.slug = "admin-secret";
        secret.durationMinutes = 30; secret.secret = true;
        secret.persist();
    }

    @Transactional
    void seedCoffee() {
        if (MeetingType.findBySlug(1L, "coffee") == null) {
            MeetingType m = new MeetingType();
            m.ownerId = 1L;
            m.name = "Coffee Chat";
            m.slug = "coffee";
            m.durationMinutes = 30;
            m.persist();
        }
    }

    @Test
    void cardRendersAbsoluteCopyLinkAndButton() {
        seedCoffee();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("data-copy-link=\"http://localhost:8080/admin/coffee\""))
                .body(containsString("copy-link-btn"));
    }

    @Test
    void pageIncludesToastAndClipboardScript() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("id=\"copy-toast\""))
                .body(containsString("navigator.clipboard"));
    }

    @Test
    void adminListShowsSecretTypeUnlikePublicLanding() {
        seedSecret();
        // Admin sees it (listAll) ...
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("Admin Visible Secret"))
                .body(containsString("secret")); // the "secret" badge

        // ... but the public landing (listPublic) does not.
        given()
            .when().get("/")
            .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.not(containsString("Admin Visible Secret")));
    }

    @Test
    void createFormExposesNewFields() {
        // The create form must offer the Plan 1b fields: min-notice, horizon, location, approval.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("name=\"minNoticeMinutes\""))
                .body(containsString("name=\"horizonDays\""))
                .body(containsString("name=\"locationType\""))   // GOOGLE_MEET/PHONE/IN_PERSON/CUSTOM dropdown
                .body(containsString("GOOGLE_MEET"))
                .body(containsString("name=\"locationDetail\""))
                .body(containsString("name=\"slotIntervalMinutes\"")) // slot cadence (blank = back-to-back)
                .body(containsString("name=\"requiresApproval\"")); // approval checkbox
    }

    @Test
    void createMeetingTypeViaFormPersistsNewFields() {
        String slug = "admin-created-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Created Via Admin")
            .formParam("slug", slug)
            .formParam("durationMinutes", "45")
            .formParam("secret", "on")
            .formParam("minNoticeMinutes", "120")
            .formParam("horizonDays", "30")
            .formParam("locationType", "PHONE")
            .formParam("locationDetail", "Call +1-555-0100")
            .formParam("slotIntervalMinutes", "15")
            .formParam("requiresApproval", "on")
            .when().post("/me/meeting-types")
            .then().statusCode(200).body(containsString(slug));

        // Persisted with the new fields (resolves via findBySlug).
        MeetingType created = MeetingType.findBySlug(1L, slug);
        org.junit.jupiter.api.Assertions.assertNotNull(created);
        org.junit.jupiter.api.Assertions.assertEquals(120, created.minNoticeMinutes);
        org.junit.jupiter.api.Assertions.assertEquals(30, created.horizonDays);
        org.junit.jupiter.api.Assertions.assertEquals(LocationType.PHONE, created.locationType);
        org.junit.jupiter.api.Assertions.assertEquals("Call +1-555-0100", created.locationDetail);
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(15), created.slotIntervalMinutes);
        org.junit.jupiter.api.Assertions.assertTrue(created.requiresApproval);
    }

    @Test
    void createFormUsesAccordionSectionsAndLocationTiles() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("class=\"collapse"))         // daisyUI accordion sections
                .body(containsString("has-[:checked]:btn-primary")) // location picker tiles
                .body(containsString("type=\"radio\" name=\"locationType\"")) // tiles are radios
                .body(containsString("value=\"GOOGLE_MEET\""));   // a tile per LocationType
    }

    @Test
    void locationTilesHaveEqualFixedHeight() {
        // daisyUI 5: location tiles are equal-size grid items — a fixed-column grid of
        // btn labels with identical padding (grid stretch + uniform btn shape = equal height).
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("grid grid-cols-2 sm:grid-cols-4"))      // fixed-column tile grid
                .body(containsString("btn btn-outline h-auto py-3 flex-col")); // uniform tile shape
    }

    @Test
    void blankSlotIntervalPersistsAsNull() {
        String slug = "admin-blank-interval-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Blank Interval")
            .formParam("slug", slug)
            .formParam("durationMinutes", "30")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            .when().post("/me/meeting-types")
            .then().statusCode(200);

        MeetingType created = MeetingType.findBySlug(1L, slug);
        org.junit.jupiter.api.Assertions.assertNotNull(created);
        org.junit.jupiter.api.Assertions.assertNull(created.slotIntervalMinutes);
    }
}
