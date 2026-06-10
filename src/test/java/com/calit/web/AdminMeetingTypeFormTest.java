package com.calit.web;

import com.calit.domain.MeetingType;
import com.calit.domain.Slugs;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class AdminMeetingTypeFormTest {

    @Test
    void createFormExposesBufferInputs() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("name=\"bufferBeforeMinutes\""))
                .body(containsString("name=\"bufferAfterMinutes\""));
    }

    @Test
    void createPersistsSeparateBuffers() {
        String slug = "buffers-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Buffered Call")
            .formParam("slug", slug)
            .formParam("durationMinutes", "30")
            .formParam("bufferBeforeMinutes", "10")
            .formParam("bufferAfterMinutes", "15")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            .when().post("/admin/meeting-types")
            .then().statusCode(200);

        MeetingType t = MeetingType.findBySlug(slug);
        assertNotNull(t);
        assertEquals(10, t.bufferBeforeMinutes);
        assertEquals(15, t.bufferAfterMinutes);
    }

    @Test
    void blankSlugIsGeneratedFromName() {
        String name = "Discovery Chat " + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", name)
            .formParam("slug", "") // blank -> server generates
            .formParam("durationMinutes", "30")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            .when().post("/admin/meeting-types")
            .then().statusCode(200);

        MeetingType t = MeetingType.findBySlug(Slugs.slugify(name));
        org.junit.jupiter.api.Assertions.assertNotNull(t);
    }

    @Test
    void duplicateGeneratedSlugGetsSuffix() {
        String name = "Repeat Topic " + System.nanoTime();
        String base = Slugs.slugify(name);
        for (int i = 0; i < 2; i++) {
            given()
                .cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", name)
                .formParam("slug", "")
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when().post("/admin/meeting-types")
                .then().statusCode(200);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(MeetingType.findBySlug(base));
        org.junit.jupiter.api.Assertions.assertNotNull(MeetingType.findBySlug(base + "-2"));
    }

    @Test
    void slugInputHasLiveFillScript() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types")
            .then()
                .statusCode(200)
                .body(containsString("data-slug-autofill")); // marker the JS hooks onto
    }
}
