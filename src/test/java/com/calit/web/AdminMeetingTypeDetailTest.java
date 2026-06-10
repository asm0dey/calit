package com.calit.web;

import com.calit.domain.MeetingType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AdminMeetingTypeDetailTest {

    @Transactional
    Long seedType(String slug) {
        MeetingType t = new MeetingType();
        t.name = "Detail Seed"; t.slug = slug; t.durationMinutes = 30;
        t.persist();
        return t.id;
    }

    @Test
    void detailPageRendersSectionsAndEditForm() {
        Long id = seedType("detail-render-" + System.nanoTime());
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin/meeting-types/" + id)
            .then()
                .statusCode(200)
                .body(containsString("Detail Seed"))
                .body(containsString("Booking fields"))
                .body(containsString("Working hours"))
                .body(containsString("Date overrides"))
                .body(containsString("name=\"name\""))
                .body(containsString("name=\"bufferBeforeMinutes\""));
    }

    @Test
    void detailPageRequiresAuth() {
        Long id = seedType("detail-auth-" + System.nanoTime());
        given().redirects().follow(false)
            .when().get("/admin/meeting-types/" + id)
            .then().statusCode(302);
    }

    @Test
    void editPersistsBasicsAndBuffers() {
        Long id = seedType("detail-edit-" + System.nanoTime());
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Renamed Type")
            .formParam("slug", "")  // blank -> regenerate from name
            .formParam("durationMinutes", "45")
            .formParam("bufferBeforeMinutes", "5")
            .formParam("bufferAfterMinutes", "20")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "PHONE")
            .formParam("locationDetail", "+1-555-0123")
            .formParam("slotIntervalMinutes", "")
            .when().post("/admin/meeting-types/" + id + "/edit")
            .then().statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertEquals("Renamed Type", t.name);
        assertEquals("renamed-type", t.slug);
        assertEquals(45, t.durationMinutes);
        assertEquals(5, t.bufferBeforeMinutes);
        assertEquals(20, t.bufferAfterMinutes);
        assertEquals(MeetingType.LocationType.PHONE, t.locationType);
    }
}
