package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.MeetingHosts;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 18: removing a co-host who still has future group bookings on the type must show a
 * keep-vs-cancel interstitial instead of removing immediately. No future bookings -> unchanged
 * (immediate removal, regression covered by CohostManageTest).
 */
@QuarkusTest
class HostRemovalInterstitialTest {

    @Inject
    BookingService bookingService;

    @Inject
    MeetingHosts meetingHosts;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private Instant nextMonday(int hour) {
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(hour, 0).atZone(AMS).toInstant();
    }

    record Seeded(MeetingType type, Long cohostId) {}

    /** Admin (id 1, "pasha") as creator + a second accepted co-host, both bookable on Monday. */
    @Transactional
    Seeded seedGroupType(String slug) {
        MultiHostFixtures.settings(1L, "pasha");
        AppUser v = MultiHostFixtures.enabledUser("volodya-" + System.nanoTime());
        MultiHostFixtures.settings(v.id, v.username);
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
        MeetingType t = MultiHostFixtures.acceptedTwoHostType(1L, v.id, slug, 60, false);
        return new Seeded(t, v.id);
    }

    private void stubOrganizerOnCreator() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt", "meet", "cal", null));
    }

    @Test
    void removeWithFutureBookingRendersConfirmAndDoesNotRemoveHost() {
        stubOrganizerOnCreator();
        var seeded = seedGroupType("interstitial-confirm-" + System.nanoTime());
        bookingService.book(
                1L, seeded.type().slug, nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + seeded.type().id + "/hosts/" + seeded.cohostId() + "/remove")
                .then()
                .statusCode(200)
                .body(containsString("1"))
                .body(containsString("choice=keep"))
                .body(containsString("choice=cancel"));

        assertNotNull(
                MeetingTypeHost.find(seeded.type().id, seeded.cohostId()), "cohost row must survive the interstitial");
    }

    @Test
    void removeWithChoiceKeepRemovesHostButLeavesBookingConfirmed() {
        stubOrganizerOnCreator();
        var seeded = seedGroupType("interstitial-keep-" + System.nanoTime());
        Booking lead = bookingService.book(
                1L, seeded.type().slug, nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + seeded.type().id + "/hosts/" + seeded.cohostId() + "/remove?choice=keep")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(seeded.type().id, seeded.cohostId()), "cohost row removed on keep");
        for (Booking row : Booking.<Booking>group(lead.groupId)) {
            assertEquals(BookingStatus.CONFIRMED, row.status, "existing booking honored on keep");
        }
    }

    @Test
    void removeWithChoiceCancelCancelsGroupAndRemovesHost() {
        stubOrganizerOnCreator();
        var seeded = seedGroupType("interstitial-cancel-" + System.nanoTime());
        Booking lead = bookingService.book(
                1L, seeded.type().slug, nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + seeded.type().id + "/hosts/" + seeded.cohostId() + "/remove?choice=cancel")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(seeded.type().id, seeded.cohostId()), "cohost row removed on cancel");
        for (Booking row : Booking.<Booking>group(lead.groupId)) {
            assertEquals(BookingStatus.CANCELLED, row.status, "group booking cancelled");
        }
    }

    @Test
    void removeWithNoFutureBookingsRemovesImmediatelyNoInterstitial() {
        var seeded = seedGroupType("interstitial-none-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/meeting-types/" + seeded.type().id + "/hosts/" + seeded.cohostId() + "/remove")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(seeded.type().id, seeded.cohostId()), "cohost row removed immediately");
    }
}
