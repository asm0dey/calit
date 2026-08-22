package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

/**
 * An account an admin has disabled must not keep a live, bookable public page. EnabledUserAugmentor
 * stops them logging in; nothing used to stop a stranger booking them, and every such booking sends
 * real email to someone who has left (calit-h8mb).
 */
@QuarkusTest
class PublicDisabledOwnerTest {

    /** The zone the disabled owner's settings row is seeded with, below. */
    private static final ZoneId OWNER_ZONE = ZoneId.of("Europe/Amsterdam");

    @AfterEach
    @Transactional
    void cleanup() {
        AppUser gone = AppUser.findByUsername("disabled-owner");
        if (gone != null) {
            AvailabilityRule.delete("ownerId", gone.id);
            MeetingType.delete("ownerId", gone.id);
            OwnerSettings.delete("ownerId", gone.id);
            AppUser.deleteById(gone.id);
        }
    }

    /** An owner who HAD working hours, a settings row and a public type -- then was switched off. */
    @Transactional
    void seedDisabledOwnerWithHours() {
        AppUser u = AppUser.create("disabled-owner", "x", false);
        u.settingsComplete = true;
        u.persist();

        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.ownerName = "Gone Person";
        s.ownerEmail = "gone@example.com";
        s.timezone = OWNER_ZONE.getId();
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = u.id;
        t.name = "Intro";
        t.slug = "intro";
        t.durationMinutes = 30;
        t.persist();

        // Hours set BEFORE the account was disabled: this is the case that is bookable today.
        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = u.id;
            r.dayOfWeek = d;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(18, 0);
            r.persist();
        }

        u.enabled = false; // managed entity -> flushed on commit
    }

    @Test
    void landingIs404() {
        seedDisabledOwnerWithHours();
        given().when().get("/disabled-owner").then().statusCode(404);
    }

    @Test
    void bookingPageIs404() {
        seedDisabledOwnerWithHours();
        given().when().get("/disabled-owner/intro").then().statusCode(404);
    }

    @Test
    void bookingPostIs404() {
        seedDisabledOwnerWithHours();
        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", "2030-01-07T09:00:00Z")
                .formParam("name", "Stranger")
                .formParam("email", "stranger@example.com")
                .when()
                .post("/disabled-owner/intro")
                .then()
                .statusCode(404);
    }

    /**
     * The JSON API is a SECOND, public and unauthenticated way in: {@code BookingResource.create}
     * resolves the {@code user} field itself and never touches {@code PublicResource.resolveOwner}, so the guard on
     * the web routes above does not reach it. Nothing downstream catches it either --
     * {@code MeetingHosts.bookable} returns true unconditionally for a single-host type, which is
     * exactly the shape this fixture seeds -- so a plain curl still booked a departed owner, wrote
     * to their calendar and mailed them (calit-h8mb).
     */
    @Test
    void apiBookingPostIs404() {
        seedDisabledOwnerWithHours();
        // A slot that is genuinely bookable: inside the type's 60-day horizon and inside the seeded
        // 09:00-18:00 hours. Without the guard this POST returns 201 -- a real booking on a departed
        // owner -- rather than an incidental 409, so the test fails loudly if the guard is removed.
        var startUtc = LocalDate.now(OWNER_ZONE)
                .plusDays(7)
                .atTime(10, 0)
                .atZone(OWNER_ZONE)
                .toInstant()
                .toString();

        given().contentType("application/json")
                .body("""
                        {"user":"disabled-owner","slug":"intro","startUtc":"%s",\
                        "inviteeName":"Stranger","inviteeEmail":"stranger@example.com",\
                        "answers":{},"turnstileToken":"tok","honeypot":""}""".formatted(startUtc))
                .when()
                .post("/api/bookings")
                .then()
                .statusCode(404);
    }
}
