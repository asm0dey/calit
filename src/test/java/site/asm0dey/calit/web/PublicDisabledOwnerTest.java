package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
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
        s.timezone = "Europe/Amsterdam";
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
}
