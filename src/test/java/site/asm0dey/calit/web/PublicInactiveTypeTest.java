package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

/**
 * A deactivated meeting type is hidden from the landing page, and must not stay bookable by typing its
 * URL -- neither the page, the form POST, nor the JSON API (calit-avqc, UC-010 BR-007).
 */
@QuarkusTest
class PublicInactiveTypeTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    // Inside the default 60-day horizon and the 09:00-18:00 hours seeded below.
    private static final String START_UTC =
            LocalDate.now(ZONE).plusDays(7).atTime(10, 0).atZone(ZONE).toInstant().toString();

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.create("typeowner", "x", false);
            u.settingsComplete = true;
            u.persist();
            OwnerSettings s = new OwnerSettings();
            s.ownerId = u.id;
            s.ownerName = "Type Owner";
            s.ownerEmail = "typeowner@example.com";
            s.timezone = ZONE.getId();
            s.persist();
            type(u.id, "live", true);
            type(u.id, "retired", false);
            for (DayOfWeek d : DayOfWeek.values()) {
                AvailabilityRule r = new AvailabilityRule();
                r.ownerId = u.id;
                r.dayOfWeek = d;
                r.startTime = LocalTime.of(9, 0);
                r.endTime = LocalTime.of(18, 0);
                r.persist();
            }
        });
    }

    private static void type(Long ownerId, String slug, boolean active) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.active = active;
        t.persist();
    }

    @Test
    void activeTwinIsBookable() {
        // Control: the fixture is bookable, so a 404 below is the guard, not a broken seed.
        given().when().get("/typeowner/live").then().statusCode(200);
    }

    @Test
    void bookingPageIs404() {
        given().when().get("/typeowner/retired").then().statusCode(404);
    }

    @Test
    void bookingFormPostIs404() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", START_UTC)
            .formParam("inviteeName", "Stranger")
            .formParam("inviteeEmail", "stranger@example.com")
            .when()
            .post("/typeowner/retired")
            .then()
            .statusCode(404);
    }

    @Test
    void apiBookingPostIs404() {
        given()
            .contentType("application/json")
            .body(apiBody("retired"))
            .when()
            .post("/api/bookings")
            .then()
            .statusCode(404);
    }

    @Test
    void manageLinkStillWorksAfterDeactivation() {
        // Deactivating stops NEW bookings; an invitee who already booked can still reach their booking.
        String token = given()
            .contentType("application/json")
            .body(apiBody("live"))
            .when()
            .post("/api/bookings")
            .then()
            .statusCode(201)
            .body("manageToken", notNullValue())
            .extract()
            .path("manageToken");
        QuarkusTransaction
            .requiringNew()
            .run(() -> MeetingType.update("active = false where slug = ?1", "live"));
        given().when().get("/booking/" + token + "/manage").then().statusCode(200);
    }

    private static String apiBody(String slug) {
        return """
                {"user":"typeowner","slug":"%s","startUtc":"%s",\
                "inviteeName":"Stranger","inviteeEmail":"stranger@example.com",\
                "answers":{},"turnstileToken":"tok","honeypot":""}"""
            .formatted(slug, START_UTC);
    }
}
