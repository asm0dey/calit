package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

/**
 * Verifies the inline client scripts (TZ_SCRIPT and CALENDAR_SCRIPT) read the page
 * language via {@code document.documentElement.lang} rather than hardcoding English.
 *
 * <p>RestAssured cannot execute JavaScript, so the test asserts on the script TEXT
 * present in the served HTML using the stable marker comments.</p>
 */
@QuarkusTest
class LayoutLocaleMarkerTest {

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("layouttest");
        if (owner == null) {
            owner = AppUser.create("layouttest", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "lt-intro");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = "Layout Test Owner";
        s.ownerEmail = "layouttest@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Layout Test Intro";
        t.slug = "lt-intro";
        t.durationMinutes = 30;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    @Test
    void bookingPagePassesLangToScripts() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().when()
                .get("/layouttest/lt-intro")
                .then()
                .statusCode(200)
                // TZ_SCRIPT stable marker
                .body(containsString("CALIT_TZ_REFORMAT"))
                // Invitee tz-picker sources the full IANA list from the browser (incl. Asia/Jerusalem)
                .body(containsString("CALIT_TZ_FULL_LIST"))
                .body(containsString("supportedValuesOf"))
                // CALENDAR_SCRIPT stable marker
                .body(containsString("CALIT_CALENDAR"))
                // Both scripts now read the page language from documentElement.lang
                .body(containsString("documentElement.lang"))
                // Calendar script derives month/weekday names via Intl
                .body(containsString("Intl.DateTimeFormat"));
    }

    /** The tz-bar text itself is server-rendered, so it must be localized (was hardcoded English). */
    @Test
    void tzBarIsLocalized() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().header("Accept-Language", "de")
                .when()
                .get("/layouttest/lt-intro")
                .then()
                .statusCode(200)
                .body(containsString("Zeiten angezeigt in:"))
                .body(org.hamcrest.Matchers.not(containsString("Times shown in:")));
    }

    /**
     * Issue #116: the time path must take its 12h/24h choice from the VIEWER's device, not from
     * the page's translation locale (bare "en" carries US defaults, hence AM/PM for everyone).
     * Words still follow documentElement.lang — only hourCycle moves.
     */
    @Test
    void timesResolveHourCycleFromTheViewersDevice() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().when()
                .get("/layouttest/lt-intro")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_HOUR_CYCLE"))
                // probes the device with an hour field — resolvedOptions() omits hourCycle without one
                .body(containsString("{hour:'numeric'}"))
                .body(containsString("resolvedOptions().hourCycle"))
                // the formatting call must carry the resolved cycle
                .body(containsString("hourCycle: HC"));
    }
}
