package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class PublicDurationPickerTest {

    private static final Long OWNER = 1L;

    @Transactional
    void seed(String slug, int defaultMinutes, int... extras) {
        // DatabaseResetCallback truncates owner_settings per test and does not reseed it — the
        // public book/landing pages 404-to-notReady without a row for the owner whose page renders.
        OwnerSettings settings = OwnerSettings.forOwner(OWNER);
        if (settings == null) {
            settings = new OwnerSettings();
            settings.ownerId = OWNER;
            settings.ownerName = "Owner";
            settings.ownerEmail = "owner@example.com";
            settings.timezone = "UTC";
            settings.persist();
        }
        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = defaultMinutes;
        t.persist();
        for (int len : extras) {
            MeetingTypeDuration d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = len;
            d.persist();
        }
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = OWNER;
            r.meetingTypeId = t.id;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.persist();
        }
    }

    private String username() {
        return ((site.asm0dey.calit.user.AppUser) site.asm0dey.calit.user.AppUser.findById(OWNER)).username;
    }

    @Test
    void aMultiDurationTypeRendersOneLinkPerLength() {
        seed("picker-multi", 30, 60, 120);
        given().when()
                .get("/" + username() + "/picker-multi")
                .then()
                .statusCode(200)
                .body(containsString("?duration=30"))
                .body(containsString("?duration=60"))
                .body(containsString("?duration=120"));
    }

    @Test
    void aSingleDurationTypeRendersNoPicker() {
        seed("picker-single", 30);
        given().when()
                .get("/" + username() + "/picker-single")
                .then()
                .statusCode(200)
                .body(not(containsString("?duration=")));
    }

    @Test
    void theChosenLengthIsCarriedIntoTheFormAsAHiddenField() {
        seed("picker-hidden", 30, 120);
        given().when()
                .get("/" + username() + "/picker-hidden?duration=120")
                .then()
                .statusCode(200)
                .body(containsString("name=\"durationMinutes\""))
                .body(containsString("value=\"120\""));
    }

    @Test
    void anUnknownOrMalformedDurationFallsBackToTheDefault() {
        seed("picker-fallback", 30, 120);
        for (String bad : new String[] {"45", "abc", ""}) {
            given().when()
                    .get("/" + username() + "/picker-fallback?duration=" + bad)
                    .then()
                    .statusCode(200)
                    .body(containsString("value=\"30\""));
        }
    }
}
