package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.CaptchaProviderConfig;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class BookPageAltchaEnabledTest {
    @InjectMock
    CalendarPort calendarPort;
    @InjectSpy
    CaptchaProviderConfig providerConfig;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("carol");
        if (owner == null) {
            owner = AppUser.create("carol", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "altcha-type");
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
        t.name = "Altcha Type";
        t.slug = "altcha-type";
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
    void bookPageRendersAltchaWidgetAndScript() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(providerConfig.provider()).thenReturn("altcha");
        seed();

        given()
            .when()
            .get("/carol/altcha-type")
            .then()
            .statusCode(200)
            .body(containsString("<altcha-widget"))
            // `challengeurl` name is silently ignored by altcha 3.x, so it must NOT appear.
            .body(containsString("challenge=\"/altcha/challenge\""))
            .body(not(containsString("challengeurl")))
            // native form control (daisyUI-styleable, light DOM) + floating display.
            .body(containsString("type=\"native\""))
            .body(containsString("display=\"floating\""))
            .body(containsString("/webjars/altcha/dist/main/altcha.i18n.min.js"))
            // No Cloudflare widget when altcha is active.
            .body(not(containsString("class=\"cf-turnstile\"")));
    }
}
