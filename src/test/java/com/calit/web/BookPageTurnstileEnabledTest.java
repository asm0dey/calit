package com.calit.web;

import com.calit.google.CalendarPort;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(BookPageTurnstileEnabledTest.TurnstileOn.class)
class BookPageTurnstileEnabledTest {

    /** Flip the owner-configurable flag on + pin a known public site key for this test only. */
    public static class TurnstileOn implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of(
                "calit.turnstile.enabled", "true",
                "calit.turnstile.site-key", "1x00000000000000000000AA");
        }
    }

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.name = "Turnstile Type"; t.slug = "turnstile-type"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    @Test
    void bookPageRendersTurnstileWidgetAndScriptWhenEnabled() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        given()
            .when().get("/book/turnstile-type")
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
