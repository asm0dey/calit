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

@QuarkusTest
class LangQueryParamTest {

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("langq");
        if (owner == null) {
            owner = AppUser.create("langq", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "intro");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = "Lang Q Owner";
        s.ownerEmail = "langq@example.com";
        s.timezone = "Asia/Jerusalem";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Intro";
        t.slug = "intro";
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

    private void mockCal() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void queryParamOverridesToHebrew() {
        mockCal();
        seed();
        given().when()
                .get("/langq/intro?lang=he")
                .then()
                .statusCode(200)
                .body(containsString("lang=\"he\""))
                .body(containsString("dir=\"rtl\""));
    }

    @Test
    void queryParamBeatsCookie() {
        mockCal();
        seed();
        // cookie says English, query says Hebrew -> query wins
        given().cookie("calit_lang", "en")
                .when()
                .get("/langq/intro?lang=he")
                .then()
                .statusCode(200)
                .body(containsString("lang=\"he\""));
    }

    @Test
    void unknownQueryParamFallsThroughToCookie() {
        mockCal();
        seed();
        given().cookie("calit_lang", "he")
                .when()
                .get("/langq/intro?lang=zz")
                .then()
                .statusCode(200)
                .body(containsString("lang=\"he\""));
    }
}
