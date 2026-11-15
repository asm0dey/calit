package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * Step 1 of a multi-slot booking: the no-JS GET-form slot picker on {@code /{user}/{slug}}.
 */
@QuarkusTest
class BookPageSetTest {
    private static final String SLUG = "set-page";
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY1 = LocalDate.now(ZONE).plusDays(7);
    private static final LocalDate DAY2 = DAY1.plusDays(1);
    private static final String A = at(DAY1, 9, 0);
    private static final String C = at(DAY1, 10, 0);
    private static final String B = at(DAY2, 9, 0);
    private static final String D = at(DAY1.plusDays(7), 9, 0);
    @InjectMock
    CalendarPort calendarPort;

    private static String at(LocalDate day, int h, int min) {
        return day.atTime(h, min).atZone(ZONE).toInstant().toString();
    }

    @BeforeEach
    void seed() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        QuarkusTransaction.requiringNew().run(() -> {
            var s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = ZONE.getId();
            s.persist();
            var t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coaching";
            t.slug = SLUG;
            t.durationMinutes = 30;
            t.minNoticeMinutes = 0;
            t.horizonDays = 30;
            t.multiSlotMin = 2;
            t.multiSlotMax = 3;
            t.multiSlotSameDay = false;
            t.persist();
            var d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = 60;
            d.persist();
            for (var day : List.of(DAY1.getDayOfWeek(), DAY2.getDayOfWeek())) {
                var r = new AvailabilityRule();
                r.ownerId = 1L;
                r.dayOfWeek = day;
                r.startTime = LocalTime.of(9, 0);
                r.endTime = LocalTime.of(12, 0);
                r.persist();
            }
        });
    }

    private static ValidatableResponse get(String query) {
        return given().when().get("/admin/" + SLUG + query).then().statusCode(200);
    }

    @Test
    void offTypeRendersRadiosUnchanged() {
        QuarkusTransaction.requiringNew().run(() -> {
            var t = MeetingType.findBySlug(1L, SLUG);
            t.multiSlotMin = null;
            t.multiSlotMax = null;
        });
        get("").body(not(containsString("name=\"pick\""))).body(containsString("name=\"startUtc\""));
    }

    @Test
    void multiSlotRendersCheckboxesInGetForm() {
        get("")
            .body(containsString("<form method=\"get\" action=\"/admin/" + SLUG + "\""))
            .body(containsString("type=\"checkbox\" name=\"pick\""))
            .body(containsString("Pick sessions"))
            .body(containsString("Your details"))
            .body(containsString("Length of the next session"))
            .body(containsString("role=\"status\" aria-atomic=\"true\""))
            .body(containsString("id=\"set-form\" data-same-day=\"0\""))
            .body(containsString("<p class=\"slot-reason\" data-reason=\"SAME_DAY\" hidden>"))
            .body(containsString("<p class=\"slot-reason\" data-reason=\"OVERLAP\" hidden>"))
            .body(containsString("<p class=\"slot-reason\" data-reason=\"MAX\" hidden>"))
            .body(containsString("CALIT_SET_PICKER"))
            .body(containsString("30 or 60 min each"))
            .body(containsString("Book 2 to 3 sessions at once, on different days."))
            .body(not(containsString("name=\"startUtc\"")))
            .body(not(containsString("name=\"inviteeEmail\"")));
    }

    @Test
    void picksSurviveDurationSwitch() {
        get("?pick=" + A + "/30&duration=60")
            .body(
                    containsString(
                            "<input type=\"hidden\" name=\"pick\" value=\"" + A + "/30\" data-date=\"" + DAY1 + "\">"
                    )
            )
            .body(containsString("name=\"remove\" value=\"" + A + "/30\""))
            .body(containsString("type=\"checkbox\" name=\"pick\" value=\"" + B + "/60\""))
            .body(not(containsString("value=\"" + B + "/30\"")));
        // Several duration values (a length button plus the form's own carrier): the button, first, wins.
        given()
            .queryParam("pick", A + "/30")
            .queryParam("duration", 60, 30)
            .when()
            .get("/admin/" + SLUG)
            .then()
            .statusCode(200)
            .body(containsString("value=\"" + B + "/60\""));
    }

    @Test
    void exactCountSameDayCopy() {
        QuarkusTransaction.requiringNew().run(() -> {
            var t = MeetingType.findBySlug(1L, SLUG);
            t.multiSlotMax = 2;
            t.multiSlotSameDay = true;
            MeetingTypeDuration.delete("meetingTypeId", t.id);
        });
        get("")
            .body(containsString("Book 2 sessions at once."))
            .body(not(containsString("on different days")))
            .body(not(containsString("min each")))
            .body(containsString("id=\"set-form\" data-same-day=\"1\""));
    }

    @Test
    void removeDropsPick() {
        get("?pick=" + A + "/30&pick=" + B + "/30&remove=" + A + "/30")
            .body(not(containsString("name=\"remove\" value=\"" + A + "/30\"")))
            .body(containsString("name=\"remove\" value=\"" + B + "/30\""))
            .body(containsString("value=\"" + B + "/30\" checked"))
            .body(not(containsString("value=\"" + A + "/30\" checked")))
            .body(containsString("1 / 3"));
    }

    @Test
    void sameDayPickDisablesThatDay() {
        get("?pick=" + A + "/30")
            .body(containsString("value=\"" + A + "/30\" checked"))
            .body(containsString("value=\"" + C + "/30\" disabled"))
            .body(not(containsString("value=\"" + B + "/30\" disabled")))
            .body(containsString("data-reason=\"SAME_DAY\">You already have a session this day</p>"))
            // DAY2 holds no pick: its same-day line is rendered for the script but hidden.
            .body(containsString("data-reason=\"SAME_DAY\" hidden>You already have a session this day</p>"));
    }

    @Test
    void overlapAtOtherLengthIsDisabled() {
        get("?pick=" + A + "/30&duration=60")
            .body(containsString("value=\"" + A + "/60\" disabled"))
            .body(containsString("data-reason=\"OVERLAP\">Overlaps a session you picked</p>"));
    }

    @Test
    void maxReachedDisablesRest() {
        get("?pick=" + A + "/30&pick=" + B + "/30&pick=" + D + "/30")
            .body(containsString("3 / 3"))
            .body(containsString("data-reason=\"MAX\">You have the maximum of 3</p>"))
            .body(containsString("value=\"" + at(DAY2, 10, 0) + "/30\" disabled"))
            .body(containsString("Continue with 3 sessions"));
    }

    @Test
    void continueDisabledBelowMin() {
        get("?pick=" + A + "/30")
            .body(containsString("Pick at least 2."))
            .body(containsString("name=\"step\" value=\"details\""))
            .body(containsString("data-continue disabled>Pick at least 2 sessions</button>"));
        get("?pick=" + A + "/30&pick=" + B + "/30")
            .body(containsString("2 sessions picked. You can add up to 1 more."))
            .body(containsString("data-continue>Continue with 2 sessions</button>"));
    }

    @Test
    void otherLengthPickShowsBadgeAndDayDot() {
        get("?pick=" + A + "/30&duration=60")
            .body(containsString("data-utc=\"" + A + "\" data-time-only=\"1\">09:00</time><span class=\"slot-badge"))
            .body(containsString(">30m</span>"))
            .body(containsString("day-slots day-slots--picked\" data-date=\"" + DAY1 + "\""))
            .body(not(containsString("day-slots day-slots--picked\" data-date=\"" + DAY2 + "\"")));
    }

    @Test
    void tamperedPickRendersPageNot500() {
        get("?pick=garbage")
            .body(containsString("role=\"alert\""))
            .body(containsString("could not be read"))
            .body(containsString("0 / 3"));
        get("?pick=%2B1000000000-12-31T23:59:59Z/30").body(containsString("role=\"alert\""));
    }
}
