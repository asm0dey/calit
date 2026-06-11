package com.calit.booking;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.BookingField;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import com.calit.google.CreatedEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookingResourceTest {

    @InjectMock
    CalendarPort calendarPort;

    // Owner tz Europe/Amsterdam. Derive a future weekday from now() so the slot is never in the past.
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final String SLOT_09_UTC = DAY.atTime(9, 0).atZone(ZONE).toInstant().toString();

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanSlot() {
        // Cancel any PENDING/CONFIRMED bookings on the test day to prevent cross-test
        // and cross-run slot pollution. Runs both before (prior-run leftovers) and
        // after (clean up so sibling test classes like RescheduleCancelTest aren't affected).
        Instant dayStart = DAY.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = DAY.plusDays(1).atStartOfDay(ZONE).toInstant();
        Booking.update("status = ?1 where status in ?2 and startUtc >= ?3 and startUtc < ?4",
                BookingStatus.CANCELLED,
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED),
                dayStart, dayEnd);
    }

    @BeforeEach
    @Transactional
    void setup() {
        if (OwnerSettings.forOwner(1L) == null) {
            OwnerSettings s = new OwnerSettings();
            s.ownerId = 1L;
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.persist();
        }
    }

    @Test
    void bookingHappyPathReturns201WithMeetLinkAndManageToken() {
        String slug = "rest-book-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-rest", "https://meet.google.com/rest-1234-xyz", "h"));

        given().contentType("application/json")
                .body("{\"user\":\"admin\",\"slug\":\"" + slug + "\",\"startUtc\":\"" + SLOT_09_UTC + "\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\","
                        + "\"answers\":{\"description\":\"Quarterly sync\"},\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(201)
                .body("meetLink", is("https://meet.google.com/rest-1234-xyz"))
                .body("status", is("CONFIRMED"))
                .body("manageToken", notNullValue())
                .body("answers.description", is("Quarterly sync"));
    }

    @Test
    void missingRequiredCustomFieldReturns422() {
        String slug = "rest-422-" + System.nanoTime();
        seedTypeWithRequiredField(slug, "company");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        // Body omits the required "company" answer -> 422 (not 409: input is wrong, slot is fine).
        given().contentType("application/json")
                .body("{\"user\":\"admin\",\"slug\":\"" + slug + "\",\"startUtc\":\"" + SLOT_09_UTC + "\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\","
                        + "\"answers\":{},\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(422)
                .body(containsString("company"));
    }

    @Test
    void seededSlotIsAvailableForBooking() {
        // The unauthenticated JSON /api/meeting-types/{slug}/available endpoint was deleted in the
        // owner-scoping refactor. Availability is now proven directly: the seeded 09:00 slot is free,
        // so booking it succeeds (a slot that was not available would yield 409 "not available").
        String slug = "rest-avail-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-avail", "https://meet.google.com/av-1-2", "h"));

        given().contentType("application/json")
                .body("{\"user\":\"admin\",\"slug\":\"" + slug + "\",\"startUtc\":\"" + SLOT_09_UTC + "\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\",\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(201);
    }

    @Test
    void unknownUserReturns404() {
        String slug = "rest-unknown-" + System.nanoTime();
        seedType(slug); // belongs to admin (owner 1)
        given().contentType("application/json")
                .body("{\"user\":\"ghost\",\"slug\":\"" + slug + "\",\"startUtc\":\"" + SLOT_09_UTC + "\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\",\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(404);
    }

    @Test
    void doubleBookReturns409() {
        String slug = "rest-conflict-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-x", "https://meet.google.com/a-b-c", "h"));

        String body = "{\"user\":\"admin\",\"slug\":\"" + slug + "\",\"startUtc\":\"" + SLOT_09_UTC + "\","
                + "\"inviteeName\":\"First\",\"inviteeEmail\":\"first@example.com\",\"turnstileToken\":\"tok\",\"honeypot\":\"\"}";
        given().contentType("application/json").body(body)
                .when().post("/api/bookings").then().statusCode(201);

        given().contentType("application/json").body(body)
                .when().post("/api/bookings").then().statusCode(409)
                .body(containsString("not available"));
    }

    @Test
    void cancelByManageTokenReturns204AndFreesSlot() {
        // Feature 5: DELETE is keyed by the manage-token returned at booking time.
        String slug = "rest-cancel-" + System.nanoTime();
        seedType(slug);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-cancel", "https://meet.google.com/cn-1-2", "h"));

        String token = given().contentType("application/json")
                .body("{\"user\":\"admin\",\"slug\":\"" + slug + "\",\"startUtc\":\"" + SLOT_09_UTC + "\","
                        + "\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\",\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(201).extract().path("manageToken");

        given().when().delete("/api/bookings/" + token).then().statusCode(204);

        // The 09:00 slot is bookable again: re-booking the same slot now succeeds (it would 409
        // "not available" if the cancel had not freed it). Replaces the deleted JSON /available probe.
        given().contentType("application/json")
                .body("{\"user\":\"admin\",\"slug\":\"" + slug + "\",\"startUtc\":\"" + SLOT_09_UTC + "\","
                        + "\"inviteeName\":\"Sam Two\",\"inviteeEmail\":\"sam2@example.com\",\"turnstileToken\":\"tok\",\"honeypot\":\"\"}")
                .when().post("/api/bookings")
                .then().statusCode(201);
    }

    void seedType(String slug) {
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = slug;
            t.slug = slug;
            t.durationMinutes = 60;
            t.minNoticeMinutes = 0;
            t.horizonDays = 50_000;
            t.locationType = LocationType.GOOGLE_MEET;
            t.requiresApproval = false;
            t.persist();
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = DAY.getDayOfWeek();
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(11, 0);
            r.meetingTypeId = t.id;
            r.persist();
        });
    }

    /** Seeds a type plus a required per-type custom field (so its form requires {@code fieldKey}). */
    void seedTypeWithRequiredField(String slug, String fieldKey) {
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = slug;
            t.slug = slug;
            t.durationMinutes = 60;
            t.minNoticeMinutes = 0;
            t.horizonDays = 50_000;
            t.locationType = LocationType.GOOGLE_MEET;
            t.requiresApproval = false;
            t.persist();
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = DAY.getDayOfWeek();
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(11, 0);
            r.meetingTypeId = t.id;
            r.persist();
            BookingField f = new BookingField();
            f.ownerId = 1L;
            f.meetingTypeId = t.id;
            f.fieldKey = fieldKey;
            f.label = "Company";
            f.type = BookingField.FieldType.SHORT_TEXT;
            f.required = true;
            f.position = 0;
            f.persist();
        });
    }
}
