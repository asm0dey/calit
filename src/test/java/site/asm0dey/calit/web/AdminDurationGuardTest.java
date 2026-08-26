package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;

/**
 * A zero or negative duration makes the slot cadence zero, and neither of {@code SlotService}'s loops
 * can advance on a zero step — an unbounded allocation loop that pins the request thread until the
 * heap gives out (calit-xjrg). Reproduced before the fix by saving {@code durationMinutes=0} through
 * this very form and then loading the public page.
 *
 * <p>The {@code min="1"} attribute on the inputs is a hint to a browser and proves nothing about a
 * POST, which is why these assert against the server.
 */
@QuarkusTest
class AdminDurationGuardTest {

    @Transactional
    Long seedType(String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Guard seed";
        t.slug = slug;
        t.durationMinutes = 30;
        t.persist();
        return t.id;
    }

    @Test
    void creatingWithAZeroDurationIsRefused() {
        var slug = "guard-create-" + System.nanoTime();
        long before = MeetingType.count();

        String body = given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Zero")
                .formParam("slug", slug)
                .formParam("durationMinutes", "0")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "CUSTOM")
                .formParam("locationDetail", "x")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();

        assertEquals(before, MeetingType.count(), "no meeting type may be created with a zero duration");
        assertTrue(body.contains("at least 1 minute"), "the owner is told why the save was refused");
    }

    @Test
    void editingToAZeroDurationIsRefused() {
        var id = seedType("guard-edit-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Guard seed")
                .formParam("slug", "guard-edit-kept")
                .formParam("durationMinutes", "0")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "CUSTOM")
                .formParam("locationDetail", "x")
                .when()
                .post("/me/meeting-types/" + id + "/edit")
                .then()
                .statusCode(200);

        assertEquals(30, ((MeetingType) MeetingType.findById(id)).durationMinutes, "the stored duration is untouched");
    }

    @Test
    void aNegativeDurationIsRefusedToo() {
        var id = seedType("guard-negative-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Guard seed")
                .formParam("slug", "guard-negative-kept")
                .formParam("durationMinutes", "-15")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "CUSTOM")
                .formParam("locationDetail", "x")
                .when()
                .post("/me/meeting-types/" + id + "/edit")
                .then()
                .statusCode(200);

        assertEquals(30, ((MeetingType) MeetingType.findById(id)).durationMinutes);
    }
}
