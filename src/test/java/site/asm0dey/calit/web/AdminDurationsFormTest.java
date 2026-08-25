package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;

@QuarkusTest
class AdminDurationsFormTest {

    @Transactional
    Long seedType(String slug, int defaultMinutes) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Durations Seed";
        t.slug = slug;
        t.durationMinutes = defaultMinutes;
        t.persist();
        return t.id;
    }

    @Test
    void savingRowsAddsThemToTheAllowedSetAlongsideTheDefault() {
        var id = seedType("durations-add-" + System.nanoTime(), 60);

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("d.duration", "30", "120")
                .formParam("d.before", "10", "45")
                .formParam("d.after", "10", "45")
                .when()
                .post("/me/meeting-types/" + id + "/durations")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertEquals(List.of(30, 60, 120), MeetingTypeDuration.allowedDurations(t));
    }

    @Test
    void reSubmittingWithABlankDurationRemovesThatLength() {
        var id = seedType("durations-remove-" + System.nanoTime(), 60);
        String cred = FormAuth.login();

        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("d.duration", "30", "120")
                .formParam("d.before", "10", "45")
                .formParam("d.after", "10", "45")
                .when()
                .post("/me/meeting-types/" + id + "/durations")
                .then()
                .statusCode(200);

        // Re-post the same rows, but the 120 row's duration is now blank -> that row is dropped.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("d.duration", "30", "")
                .formParam("d.before", "10", "45")
                .formParam("d.after", "10", "45")
                .when()
                .post("/me/meeting-types/" + id + "/durations")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertEquals(List.of(30, 60), MeetingTypeDuration.allowedDurations(t));
    }

    @Test
    void clearingTheDefaultsRowDropsItsBuffersButNeverTheDuration() {
        var id = seedType("durations-default-" + System.nanoTime(), 60);
        String cred = FormAuth.login();

        // Save a row for the default (60) carrying buffer overrides.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("d.duration", "60")
                .formParam("d.before", "5")
                .formParam("d.after", "5")
                .when()
                .post("/me/meeting-types/" + id + "/durations")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findById(id);
        assertEquals(List.of(60), MeetingTypeDuration.allowedDurations(t));
        MeetingTypeDuration savedRow = MeetingTypeDuration.findRow(id, 60);
        assertEquals(5, savedRow.bufferBeforeMinutes);
        assertEquals(5, savedRow.bufferAfterMinutes);

        // Re-post the 60 row blank -> its buffer row is gone, but 60 stays in the allowed set
        // because the set membership comes from the type's own durationMinutes, not the row.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("d.duration", "")
                .formParam("d.before", "")
                .formParam("d.after", "")
                .when()
                .post("/me/meeting-types/" + id + "/durations")
                .then()
                .statusCode(200);

        t = MeetingType.findById(id);
        assertEquals(List.of(60), MeetingTypeDuration.allowedDurations(t), "the default is never removable");
        assertNull(MeetingTypeDuration.findRow(id, 60), "the buffer-override row is gone");
    }

    @Test
    void detailPageRendersOneFilledRowPerMemberOfTheUnionPlusOneSpare() {
        var id = seedType("durations-render-" + System.nanoTime(), 60);
        String cred = FormAuth.login();

        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("d.duration", "30")
                .formParam("d.before", "10")
                .formParam("d.after", "10")
                .when()
                .post("/me/meeting-types/" + id + "/durations")
                .then()
                .statusCode(200);

        String body = given().cookie("quarkus-credential", cred)
                .when()
                .get("/me/meeting-types/" + id)
                .then()
                .statusCode(200)
                .body(containsString("Allowed durations"))
                .extract()
                .body()
                .asString();

        // One filled row per allowed length (30, 60) plus one blank spare row -> 3 duration inputs.
        var occurrences = body.split("name=\"d\\.duration\"", -1).length - 1;
        assertEquals(3, occurrences, "one row per union member (30, 60) plus one empty spare");
        assertEquals(
                1,
                body.split("name=\"d\\.duration\" value=\"30\"", -1).length - 1,
                "a filled row for the added length 30");
        assertEquals(
                1,
                body.split("name=\"d\\.duration\" value=\"60\"", -1).length - 1,
                "a filled row for the implicit default 60");
    }
}
