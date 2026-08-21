package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.user.AppUser;

/**
 * A co-host picks their OWN write calendar for a shared type: it lands on their meeting_type_host
 * row, and a calendar belonging to someone else is refused.
 */
@QuarkusTest
class SharedWriteCalendarTest {

    @AfterEach
    @Transactional
    void cleanup() {
        MeetingTypeHost.delete(
                "meetingTypeId in (select t.id from MeetingType t where t.slug like ?1)", "shared-cal-%");
        MeetingType.delete("slug like ?1", "shared-cal-%");
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
        AppUser.delete("username", "shared-cal-creator");
        AppUser.delete("username", "shared-cal-cohost2");
    }

    @Test
    void cohostSavesTheirOwnCalendar() {
        var credId = seedOwnerCalendars();
        var typeId = seedSharedType();

        saveBuffers(typeId, credId + ":work@example.com").statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertEquals(credId, h.googleCredentialId);
        assertEquals("work@example.com", h.googleCalendarId);
    }

    @Test
    void aCalendarThatIsNotTheirsIsRejected() {
        seedOwnerCalendars();
        var typeId = seedSharedType();
        var foreignCredId = foreignCredentialId(typeId);

        saveBuffers(typeId, foreignCredId + ":creator@example.com").statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertNull(h.googleCredentialId);
        assertNull(h.googleCalendarId);
    }

    @Test
    void savingBuffersKeepsADanglingOverride() {
        // A Co-host editing only their buffers must not lose a write override whose calendar they
        // happen to have unticked: the dangling option posts "keep".
        var credId = seedOwnerCalendars();
        var typeId = seedSharedType();
        setHostOverride(typeId, credId, "unticked@example.com");

        saveBuffers(typeId, "keep").statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertEquals(credId, h.googleCredentialId);
        assertEquals("unticked@example.com", h.googleCalendarId);
    }

    @Test
    void anUnrelatedSaveWithNoWriteCalendarFieldKeepsADanglingOverrideOnADisconnectedAccount() {
        // Zero selected calendars means sharedAvailability.html never renders the
        // <select name="writeCalendar"> at all (it's gated behind writeCalendars.size > 0), so an
        // ordinary buffers save by a Co-host whose Google account is fully disconnected posts NO
        // writeCalendar field at all -- not "keep" and not "". That must not be read as "clear the
        // override": it's exactly the Co-host who most needs the dangling override to survive so
        // they can fix it once they reconnect.
        var typeId = seedSharedType();
        setHostOverride(typeId, null, "was-on-a-disconnected-account@example.com");

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("bufferBeforeMinutes", "")
                .formParam("bufferAfterMinutes", "")
                // Deliberately no "writeCalendar" formParam at all.
                .when()
                .post("/me/shared/" + typeId + "/buffers")
                .then()
                .statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertNull(h.googleCredentialId);
        assertEquals("was-on-a-disconnected-account@example.com", h.googleCalendarId);
    }

    @Test
    void savingTheCohostsCalendarLeavesTheCreatorsOverrideAndTheOtherCohostsRowUntouched() {
        // The structural guarantee (requireAcceptedHost keys off currentOwner.id(), so this save can
        // never reach MeetingType's own columns or another host's row) is pinned here rather than
        // left implicit in WriteTargetResolver.writeOverride: a reader of this test file should see
        // the multi-tenancy boundary without having to trace the resolver to be convinced of it.
        var credId = seedOwnerCalendars();
        var typeId = seedSharedType();
        setCreatorOverride(typeId, "creator-own-override@example.com");
        var otherCohostId = addSecondCohostWithOverride(typeId, "other-cohost-override@example.com");

        saveBuffers(typeId, credId + ":work@example.com").statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        assertEquals(credId, h.googleCredentialId);
        assertEquals("work@example.com", h.googleCalendarId);

        MeetingType t = MeetingType.findById(typeId);
        assertNull(t.googleCredentialId);
        assertEquals("creator-own-override@example.com", t.googleCalendarId);

        MeetingTypeHost other = MeetingTypeHost.find(typeId, otherCohostId);
        assertNull(other.googleCredentialId);
        assertEquals("other-cohost-override@example.com", other.googleCalendarId);
    }

    /** Sets the type creator's OWN write override directly on {@code MeetingType} -- never touched by a co-host save. */
    @Transactional
    void setCreatorOverride(Long typeId, String calendarId) {
        MeetingType t = MeetingType.findById(typeId);
        t.googleCalendarId = calendarId;
        t.persist();
    }

    /** A second, distinct co-host on the same type, with their own pre-existing override. Returns their owner id. */
    @Transactional
    Long addSecondCohostWithOverride(Long typeId, String calendarId) {
        AppUser other = AppUser.create("shared-cal-cohost2", "x", false);
        other.persist();
        MeetingTypeHost h = MeetingTypeHost.of(typeId, other.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED);
        h.googleCalendarId = calendarId;
        h.persist();
        return other.id;
    }

    @Transactional
    void setHostOverride(Long typeId, Long credId, String calendarId) {
        MeetingTypeHost h = MeetingTypeHost.find(typeId, 1L);
        h.googleCredentialId = credId;
        h.googleCalendarId = calendarId;
        h.persist();
    }

    private io.restassured.response.ValidatableResponse saveBuffers(Long typeId, String writeCalendar) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("bufferBeforeMinutes", "")
                .formParam("bufferAfterMinutes", "")
                .formParam("writeCalendar", writeCalendar)
                .when()
                .post("/me/shared/" + typeId + "/buffers")
                .then();
    }

    /** Owner 1 (the logged-in co-host): one account with a default and a second calendar. */
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

    /** A type owned by someone else, with owner 1 as an ACCEPTED co-host. */
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
        MeetingTypeHost.of(t.id, creator.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED)
                .persist();
        return t.id;
    }

    /** A connected calendar owned by the type's creator, not by owner 1. */
    @Transactional
    Long foreignCredentialId(Long typeId) {
        MeetingType t = MeetingType.findById(typeId);
        GoogleCredential c = new GoogleCredential();
        c.ownerId = t.ownerId;
        c.refreshToken = "rt";
        c.googleSub = "sub-shared-cal-creator-" + UUID.randomUUID();
        c.persist();
        persistCalendar(t.ownerId, c.id, "creator@example.com", true);
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
