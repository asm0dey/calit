package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.user.AppUser;

/**
 * The Creator picks a write override per type: a calendar that isn't theirs is refused, a dangling
 * override is warned about and survives an unrelated save, and moving a type with upcoming bookings
 * says those bookings stay where they were created.
 */
@QuarkusTest
class AdminWriteCalendarTest {

    @AfterEach
    @Transactional
    void cleanup() {
        Booking.delete("meetingTypeId in (select t.id from MeetingType t where t.slug like ?1)", "write-cal-%");
        MeetingType.delete("slug like ?1", "write-cal-%");
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
        AppUser.delete("username", "write-cal-other");
    }

    @Test
    void savesTheChosenCalendar() {
        var credId = seedOwnerCalendars();
        var typeId = seedType(null, null);

        edit(typeId, credId + ":work@example.com").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertEquals(credId, t.googleCredentialId);
        assertEquals("work@example.com", t.googleCalendarId);
    }

    @Test
    void blankClearsTheOverride() {
        var credId = seedOwnerCalendars();
        var typeId = seedType(credId, "work@example.com");

        edit(typeId, "").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertNull(t.googleCredentialId);
        assertNull(t.googleCalendarId);
    }

    @Test
    void aForeignCalendarIsRejectedAndNothingIsPersisted() {
        seedOwnerCalendars();
        var foreignCredId = seedForeignCalendar();
        var typeId = seedType(null, null);

        edit(typeId, foreignCredId + ":foreign@example.com").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertNull(t.googleCredentialId);
        assertNull(t.googleCalendarId);
    }

    @Test
    void aDanglingOverrideIsWarnedAboutOnTheForm() {
        var credId = seedOwnerCalendars();
        var typeId = seedType(credId, "unticked@example.com");

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + typeId)
                .then()
                .statusCode(200)
                .body(containsString("data-write-calendar-dangling"))
                .body(containsString("value=\"keep\""));
    }

    @Test
    void aDisconnectedAccountAlsoWarns() {
        // The FK nulled google_credential_id and left the calendar id: still a dangling override.
        seedOwnerCalendars();
        var typeId = seedType(null, "was-on-a-disconnected-account@example.com");

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + typeId)
                .then()
                .statusCode(200)
                .body(containsString("data-write-calendar-dangling"));
    }

    @Test
    void anUnrelatedSaveKeepsADanglingOverride() {
        // Renaming a type must not erase a write override the Host never touched: the dangling
        // option carries value="keep", and "keep" leaves both columns alone.
        var credId = seedOwnerCalendars();
        var typeId = seedType(credId, "unticked@example.com");

        edit(typeId, "keep").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertEquals(credId, t.googleCredentialId);
        assertEquals("unticked@example.com", t.googleCalendarId);
    }

    @Test
    void movingATypeWithUpcomingBookingsSaysTheyStayBehind() {
        var credId = seedOwnerCalendars();
        var typeId = seedType(credId, "default@example.com");
        seedUpcomingBooking(typeId, credId, "default@example.com");

        edit(typeId, credId + ":work@example.com")
                .statusCode(200)
                .body(containsString("stay on the calendar they were created on"));
    }

    @Test
    void createUsesTheChosenCalendar() {
        var credId = seedOwnerCalendars();
        var slug = "write-cal-created-" + UUID.randomUUID();

        create(slug, credId + ":work@example.com").statusCode(200);

        MeetingType t = MeetingType.find("slug", slug).firstResult();
        assertEquals(credId, t.googleCredentialId);
        assertEquals("work@example.com", t.googleCalendarId);
    }

    @Test
    void createRefusesAForeignCalendar() {
        seedOwnerCalendars();
        var foreignCredId = seedForeignCalendar();
        var slug = "write-cal-refused-" + UUID.randomUUID();

        create(slug, foreignCredId + ":foreign@example.com").statusCode(200);

        assertNull(MeetingType.find("slug", slug).firstResult());
    }

    private io.restassured.response.ValidatableResponse edit(Long typeId, String writeCalendar) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Write cal")
                .formParam("slug", "write-cal-" + typeId)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("writeCalendar", writeCalendar)
                .when()
                .post("/me/meeting-types/" + typeId + "/edit")
                .then();
    }

    private io.restassured.response.ValidatableResponse create(String slug, String writeCalendar) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Write cal")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("writeCalendar", writeCalendar)
                .when()
                .post("/me/meeting-types")
                .then();
    }

    /** Owner 1: one account, a default write target and a second selected calendar. Returns the credential id. */
    @Transactional
    Long seedOwnerCalendars() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-write-cal-" + UUID.randomUUID();
        c.persist();
        persistCalendar(1L, c.id, "default@example.com", true);
        persistCalendar(1L, c.id, "work@example.com", false);
        return c.id;
    }

    /** Another user's connected calendar — must never be selectable for owner 1. */
    @Transactional
    Long seedForeignCalendar() {
        AppUser other = AppUser.create("write-cal-other", "x", false);
        other.persist();
        GoogleCredential c = new GoogleCredential();
        c.ownerId = other.id;
        c.refreshToken = "rt";
        c.googleSub = "sub-write-cal-foreign-" + UUID.randomUUID();
        c.persist();
        persistCalendar(other.id, c.id, "foreign@example.com", true);
        return c.id;
    }

    @Transactional
    Long seedType(Long credId, String calendarId) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Write cal";
        t.slug = "write-cal-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.locationType = MeetingType.LocationType.PHONE;
        t.googleCredentialId = credId;
        t.googleCalendarId = calendarId;
        t.persist();
        return t.id;
    }

    /** One CONFIRMED booking a week out whose Google event lives on {@code calendarId}. */
    @Transactional
    void seedUpcomingBooking(Long typeId, Long credId, String calendarId) {
        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = typeId;
        b.inviteeName = "Ada";
        b.inviteeEmail = "ada@example.com";
        b.startUtc = Instant.now().plus(7, ChronoUnit.DAYS);
        b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.googleEventId = "evt-staying";
        b.googleCredentialId = credId;
        b.googleCalendarId = calendarId;
        b.persist();
    }

    private static void persistCalendar(Long ownerId, Long credId, String calId, boolean writeTarget) {
        GoogleCalendar g = new GoogleCalendar();
        g.ownerId = ownerId;
        g.googleCredentialId = credId;
        g.googleCalendarId = calId;
        g.summary = calId;
        g.readForBusy = writeTarget;
        g.writeTarget = writeTarget;
        g.persist();
    }
}
