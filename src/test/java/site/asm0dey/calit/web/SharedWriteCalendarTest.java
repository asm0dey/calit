package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.google.WriteTargetResolver;
import site.asm0dey.calit.user.AppUser;

/**
 * Only the Creator has a write-calendar override on a shared type (ADR 0007): a Co-host gets no
 * picker and a posted value changes nothing; a calendar belonging to someone else is refused.
 */
@QuarkusTest
class SharedWriteCalendarTest {
    @AfterEach
    @Transactional
    void cleanup() {
        Booking.delete("meetingTypeId in (select t.id from MeetingType t where t.slug like ?1)", "shared-cal-%");
        MeetingTypeHost.delete("meetingTypeId in (select t.id from MeetingType t where t.slug like ?1)", "shared-cal-%");
        MeetingType.delete("slug like ?1", "shared-cal-%");
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
        AppUser.delete("username", "shared-cal-creator");
        AppUser.delete("username", "shared-cal-cohost2");
        AppUser.delete("username", "shared-cal-foreign");
    }

    @Test
    void cohostSeesNoWritePicker() {
        var credId = seedOwnerCalendars();
        var typeId = seedSharedType();

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/shared/" + typeId + "/availability")
            .then()
            .statusCode(200)
            .body(not(containsString("name=\"writeCalendar\"")));
        assertThat(credId).isNotNull();
    }

    @Test
    void cohostPostingAWriteCalendarChangesNothing() {
        var credId = seedOwnerCalendars();
        var typeId = seedSharedType();

        saveBuffers(typeId, credId + ":work@example.com").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertThat(t.googleCredentialId).isNull();
        assertThat(t.googleCalendarId).isNull();
        assertThat(MeetingTypeHost.<MeetingTypeHost>forType(typeId)).hasSize(2);
    }

    @Test
    void creatorStillSeesWriteCalendarPicker() {
        // A Creator reaches this page through their own accepted CREATOR host row.
        var credId = seedOwnerCalendars();
        var typeId = seedOwnTypeWithCohost();

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/shared/" + typeId + "/availability")
            .then()
            .statusCode(200)
            .body(containsString("name=\"writeCalendar\""));

        saveBuffers(typeId, credId + ":work@example.com").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertThat(t.googleCredentialId).isEqualTo(credId);
        assertThat(t.googleCalendarId).isEqualTo("work@example.com");
    }

    @Test
    void aCalendarThatIsNotTheirsIsRejected() {
        seedOwnerCalendars();
        var typeId = seedOwnTypeWithCohost();
        var foreignCredId = foreignCredentialId();

        saveBuffers(typeId, foreignCredId + ":creator@example.com").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertThat(t.googleCredentialId).isNull();
        assertThat(t.googleCalendarId).isNull();
    }

    @Test
    void savingBuffersKeepsADanglingOverride() {
        var credId = seedOwnerCalendars();
        var typeId = seedOwnTypeWithCohost();
        setCreatorOverride(typeId, credId, "unticked@example.com");

        saveBuffers(typeId, "keep").statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertThat(t.googleCredentialId).isEqualTo(credId);
        assertThat(t.googleCalendarId).isEqualTo("unticked@example.com");
    }

    @Test
    void anUnrelatedSaveWithNoWriteCalendarFieldKeepsADanglingOverrideOnADisconnectedAccount() {
        // Zero selected calendars means the <select name="writeCalendar"> is never rendered, so an
        // ordinary buffers save posts NO writeCalendar field -- that must not clear the override.
        var typeId = seedOwnTypeWithCohost();
        setCreatorOverride(typeId, null, "was-on-a-disconnected-account@example.com");

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("bufferBeforeMinutes", "")
            .formParam("bufferAfterMinutes", "")
            // Deliberately no "writeCalendar" formParam at all.
            .when()
            .post("/me/shared/" + typeId + "/buffers")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findById(typeId);
        assertThat(t.googleCredentialId).isNull();
        assertThat(t.googleCalendarId).isEqualTo("was-on-a-disconnected-account@example.com");
    }

    @Test
    void aDanglingOverrideIsWarnedAboutOnTheForm() {
        var credId = seedOwnerCalendars();
        var typeId = seedOwnTypeWithCohost();
        setCreatorOverride(typeId, credId, "unticked@example.com");

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/shared/" + typeId + "/availability")
            .then()
            .statusCode(200)
            .body(containsString("data-write-calendar-dangling"))
            .body(containsString("value=\"" + WriteTargetResolver.KEEP + "\""));
    }

    @Test
    void aLiveOverrideRendersAsTheSelectedOption() {
        var credId = seedOwnerCalendars();
        var typeId = seedOwnTypeWithCohost();
        setCreatorOverride(typeId, credId, "work@example.com");

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/shared/" + typeId + "/availability")
            .then()
            .statusCode(200)
            .body(containsString("value=\"" + credId + ":work@example.com\" selected"))
            .body(not(containsString("value=\"\" selected")));
    }

    @Test
    void movingTheCalendarSaysUpcomingBookingsStayBehind() {
        var credId = seedOwnerCalendars();
        var typeId = seedOwnTypeWithCohost();
        setCreatorOverride(typeId, credId, "default@example.com");
        seedUpcomingBooking(typeId, credId, "default@example.com");

        saveBuffers(typeId, credId + ":work@example.com")
            .statusCode(200)
            .body(containsString("stay on the calendar they were created on"));
    }

    @Test
    void resavingTheSameCalendarDoesNotClaimBookingsStayedBehind() {
        // A booking on a calendar that is neither the current override nor the move target still sits
        // elsewhere, but a no-op save moved nothing, so it must not say "they stay behind".
        var credId = seedOwnerCalendars();
        var typeId = seedOwnTypeWithCohost();
        setCreatorOverride(typeId, credId, "default@example.com");
        seedUpcomingBooking(typeId, credId, "elsewhere@example.com");

        saveBuffers(typeId, credId + ":default@example.com")
            .statusCode(200)
            .body(not(containsString("stay on the calendar they were created on")));
        // Positive control: an actual move still says so.
        saveBuffers(typeId, credId + ":work@example.com")
            .statusCode(200)
            .body(containsString("stay on the calendar they were created on"));
    }

    /**
     * Sets the Creator's write override directly on {@code MeetingType}.
     */
    @Transactional
    void setCreatorOverride(Long typeId, Long credId, String calendarId) {
        MeetingType t = MeetingType.findById(typeId);
        t.googleCredentialId = credId;
        t.googleCalendarId = calendarId;
        t.persist();
    }

    private io.restassured.response.ValidatableResponse saveBuffers(Long typeId, String writeCalendar) {
        return given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("bufferBeforeMinutes", "")
            .formParam("bufferAfterMinutes", "")
            .formParam("writeCalendar", writeCalendar)
            .when()
            .post("/me/shared/" + typeId + "/buffers")
            .then();
    }

    /**
     * Owner 1 (the logged-in co-host): one account with a default and a second calendar.
     */
    @Transactional
    Long seedOwnerCalendars() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-shared-cal-" + UUID.randomUUID();
        c.persist();
        persistCalendar(1L, c.id, "default@example.com", true);
        persistCalendar(1L, c.id, "work@example.com", false);
        return c.id;
    }

    /**
     * A type owned by someone else, with owner 1 as an ACCEPTED co-host.
     */
    @Transactional
    Long seedSharedType() {
        AppUser creator = AppUser.create("shared-cal-creator", "x", false);
        creator.persist();
        MeetingType t = new MeetingType();
        t.ownerId = creator.id;
        t.name = "Shared cal";
        t.slug = "shared-cal-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.locationType = MeetingType.LocationType.PHONE;
        t.persist();
        MeetingTypeHost.of(t.id, creator.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        return t.id;
    }

    /**
     * A type owned by owner 1 (the logged-in user) that also carries a real ACCEPTED CREATOR host
     * row for owner 1 -- that row only materializes once a co-host exists (see {@code
     * AdminResource#hostRows}), which is exactly what lets a Creator reach the co-host page's
     * {@code requireAcceptedHost} guard at all.
     */
    @Transactional
    Long seedOwnTypeWithCohost() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Shared cal own";
        t.slug = "shared-cal-own-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.locationType = MeetingType.LocationType.PHONE;
        t.persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        AppUser other = AppUser.create("shared-cal-cohost2", "x", false);
        other.persist();
        MeetingTypeHost.of(t.id, other.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        return t.id;
    }

    /**
     * One CONFIRMED booking a week out whose Google event lives on {@code calendarId}.
     */
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

    /**
     * A connected calendar owned by another user, not by owner 1.
     */
    @Transactional
    Long foreignCredentialId() {
        AppUser other = AppUser.create("shared-cal-foreign", "x", false);
        other.persist();
        GoogleCredential c = new GoogleCredential();
        c.ownerId = other.id;
        c.refreshToken = "rt";
        c.googleSub = "sub-shared-cal-creator-" + UUID.randomUUID();
        c.persist();
        persistCalendar(other.id, c.id, "creator@example.com", true);
        return c.id;
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
