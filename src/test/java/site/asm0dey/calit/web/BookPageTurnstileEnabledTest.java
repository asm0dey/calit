package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
@TestProfile(BookPageTurnstileEnabledTest.TurnstileOn.class)
class BookPageTurnstileEnabledTest {

    /** Flip the owner-configurable flag on + pin a known public site key for this test only. */
    public static class TurnstileOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.turnstile.enabled", "true",
                    "calit.turnstile.site-key", "1x00000000000000000000AA");
        }
    }

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) {
            owner = AppUser.create("bob", "x", false);
            owner.persistAndFlush();
        } // create() builds but does not persist; flush to assign id
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "turnstile-type");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Turnstile Type";
        t.slug = "turnstile-type";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    @Test
    void bookPageRendersTurnstileWidgetAndScriptWhenEnabled() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().when()
                .get("/bob/turnstile-type")
                .then()
                .statusCode(200)
                // The Turnstile widget div carries the public site key ...
                .body(containsString("class=\"cf-turnstile\""))
                .body(containsString("data-sitekey=\"1x00000000000000000000AA\""))
                // ... and the loader script is present.
                .body(containsString("challenges.cloudflare.com/turnstile/v0/api.js"))
                // Honeypot still present.
                .body(containsString("name=\"website\""));
    }
}
