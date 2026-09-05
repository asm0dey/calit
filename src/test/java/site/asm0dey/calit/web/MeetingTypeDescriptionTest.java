package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * GH #128 — the owner-authored note on a meeting type. The {@code description} column and its two
 * public renders predate this; what was missing is any admin form that writes it. These tests pin
 * the round trip: admin form -> column -> public booking page and landing card.
 */
@QuarkusTest
class MeetingTypeDescriptionTest {

    private static final String NOTE = "Please select the appropriate date and time for our therapy session.";

    /** The public pages 404-ish into "not ready yet" until the owner has settings. */
    @Transactional
    void seedOwnerSettings() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    /** Create a type through the real admin form, returning its slug. */
    private static String createType(String slug, String description) {
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Described Type")
                .formParam("slug", slug)
                .formParam("description", description)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);
        return slug;
    }

    @Test
    void createFormExposesDescriptionField() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("name=\"description\""));
    }

    @Test
    void createPersistsDescription() {
        var slug = "desc-create-" + System.nanoTime();
        createType(slug, NOTE);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(NOTE, t.description);
    }

    @Test
    void blankDescriptionIsStoredAsNull() {
        var slug = "desc-blank-" + System.nanoTime();
        createType(slug, "   ");

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertNull(t.description);
    }

    @Test
    void detailFormShowsCurrentDescription() {
        var slug = "desc-detail-" + System.nanoTime();
        createType(slug, NOTE);
        MeetingType t = MeetingType.findBySlug(1L, slug);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + t.id)
                .then()
                .statusCode(200)
                .body(containsString("name=\"description\""))
                .body(containsString(NOTE));
    }

    @Test
    void editUpdatesDescription() {
        var slug = "desc-edit-" + System.nanoTime();
        createType(slug, "old note");
        MeetingType t = MeetingType.findBySlug(1L, slug);

        String body = given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Described Type")
                .formParam("slug", slug)
                .formParam("description", NOTE)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types/" + t.id + "/edit")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        // Asserted on the re-rendered detail page, not a re-read entity: the test thread's
        // persistence context still holds the pre-edit instance, so findBySlug would hand back a
        // stale first-level-cache hit rather than the committed row.
        assertTrue(body.contains(NOTE), "edited note should render back into the detail form");
        assertFalse(body.contains("old note"));
    }

    @Test
    void bookingPageShowsTheNote() {
        seedOwnerSettings();
        var slug = "desc-public-" + System.nanoTime();
        createType(slug, NOTE);

        given().when().get("/admin/" + slug).then().statusCode(200).body(containsString(NOTE));
    }

    @Test
    void bookingPageOmitsAnAbsentNote() {
        seedOwnerSettings();
        var slug = "desc-public-blank-" + System.nanoTime();
        createType(slug, "");

        given().when().get("/admin/" + slug).then().statusCode(200).body(not(containsString(NOTE)));
    }

    @Test
    void landingCardShowsTheNote() {
        seedOwnerSettings();
        var slug = "desc-landing-" + System.nanoTime();
        createType(slug, NOTE);

        given().when().get("/admin").then().statusCode(200).body(containsString(NOTE));
    }
}
