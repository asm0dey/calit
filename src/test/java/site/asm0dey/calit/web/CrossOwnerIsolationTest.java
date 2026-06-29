package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class CrossOwnerIsolationTest {

    @Inject
    SlotService slotService;

    @Inject
    site.asm0dey.calit.booking.BookingService bookingService;

    // The busy-set test calls availableSlots() outside an HTTP request, where the real
    // GoogleCalendarPort.isConnected() would touch the @RequestScoped CurrentOwner. Mock the port
    // to the degraded (not-connected) path so the busy-set is just the owner's HELD bookings.
    @io.quarkus.test.InjectMock
    site.asm0dey.calit.google.CalendarPort calendarPort;

    /**
     * Seeds owner B (a second AppUser) with a meeting type, a rule, a pending booking, and settings.
     * Idempotent: if "ownerb" already exists (from a prior test method in this run), reuses their
     * first meeting type and first pending booking rather than inserting duplicates.
     */
    @Transactional
    long[] seedOwnerB() {
        AppUser b = AppUser.findByUsername("ownerb");
        if (b == null) {
            b = AppUser.create("ownerb", "x", false);
            b.enabled = true;
            b.persist();
        }

        if (OwnerSettings.forOwner(b.id) == null) {
            OwnerSettings s = new OwnerSettings();
            s.ownerId = b.id;
            s.ownerName = "Owner B";
            s.ownerEmail = "ownerb@x.com";
            s.timezone = "UTC";
            s.persist();
        }

        MeetingType t = MeetingType.<MeetingType>find("ownerId = ?1 and slug = ?2", b.id, "b-strategy")
                .firstResult();
        if (t == null) {
            t = new MeetingType();
            t.ownerId = b.id;
            t.name = "B Secret Strategy";
            t.slug = "b-strategy";
            t.durationMinutes = 30;
            t.persist();
        }

        if (AvailabilityRule.forMeetingType(b.id, t.id, DayOfWeek.MONDAY).isEmpty()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = b.id;
            r.meetingTypeId = t.id;
            r.dayOfWeek = DayOfWeek.MONDAY;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.persist();
        }

        final Long typeId = t.id;
        Booking bk = Booking.<Booking>find(
                        "ownerId = ?1 and meetingTypeId = ?2 and status = ?3", b.id, typeId, BookingStatus.PENDING)
                .firstResult();
        if (bk == null) {
            bk = new Booking();
            bk.ownerId = b.id;
            bk.meetingTypeId = t.id;
            bk.inviteeName = "B Invitee";
            bk.inviteeEmail = "binvitee@x.com";
            bk.startUtc = Instant.now().plusSeconds(86400);
            bk.endUtc = bk.startUtc.plusSeconds(1800);
            bk.createdAt = Instant.now();
            bk.manageToken = java.util.UUID.randomUUID().toString();
            bk.status = BookingStatus.PENDING;
            bk.persist();
        }

        return new long[] {t.id, bk.id};
    }

    @Test
    void ownerBMeetingTypeAbsentFromOwnerAList() {
        seedOwnerB();
        given().cookie("quarkus-credential", FormAuth.login()) // owner A
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(not(containsString("B Secret Strategy")));
    }

    @Test
    void ownerADirectGetOfOwnerBTypeIs404() {
        var typeId = seedOwnerB()[0];
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + typeId)
                .then()
                .statusCode(404);
    }

    @Test
    void ownerACannotEditOwnerBType() {
        var typeId = seedOwnerB()[0];
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Hijacked")
                .formParam("slug", "hijacked")
                .formParam("durationMinutes", "15")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types/" + typeId + "/edit")
                .then()
                .statusCode(404);
    }

    @Test
    void ownerACannotDeleteOwnerBType() {
        var typeId = seedOwnerB()[0];
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .post("/me/meeting-types/" + typeId + "/delete")
                .then()
                .statusCode(404);
    }

    @Test
    void ownerBPendingBookingAbsentAndApproveIs404() {
        var bookingId = seedOwnerB()[1];
        // B's pending booking never appears in A's pending queue ...
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/pending")
                .then()
                .statusCode(200)
                .body(not(containsString("B Invitee")));
        // ... and A cannot approve it.
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .post("/me/bookings/" + bookingId + "/approve")
                .then()
                .statusCode(404);
    }

    @Test
    void ownerASettingsAreOwnersOwnNotOwnerBs() {
        seedOwnerB();
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .body(not(containsString("ownerb@x.com")));
    }

    /**
     * FIX 1 — owner-scoped busy-set: owner B's HELD booking must NOT appear in owner A's busy-set,
     * so A still sees the overlapping slot as FREE. Each owner has their own Google calendar; one
     * owner's bookings never block another's availability.
     */
    @Test
    @TestTransaction
    void ownerBHeldBookingDoesNotBlockOwnerAsSlots() {
        org.mockito.Mockito.when(calendarPort.isConnected(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(false); // degraded: no Google
        AppUser a = AppUser.create("ownera-busy", "x", false);
        a.enabled = true;
        a.createdAt = Instant.now();
        a.persist();
        OwnerSettings sa = new OwnerSettings();
        sa.ownerId = a.id;
        sa.ownerName = "A";
        sa.ownerEmail = "a@x.com";
        sa.timezone = "UTC";
        sa.persist();
        MeetingType ta = new MeetingType();
        ta.ownerId = a.id;
        ta.name = "A Intro";
        ta.slug = "a-intro";
        ta.durationMinutes = 30;
        ta.persist();

        // A wide weekly rule for A's type on the target weekday.
        var day = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3);
        AvailabilityRule ra = new AvailabilityRule();
        ra.ownerId = a.id;
        ra.meetingTypeId = ta.id;
        ra.dayOfWeek = day.getDayOfWeek();
        ra.startTime = LocalTime.of(9, 0);
        ra.endTime = LocalTime.of(17, 0);
        ra.persist();

        // Owner B holds a booking at 10:00-10:30 on that same day (against B's own meeting type).
        AppUser b = AppUser.create("ownerb-busy", "x", false);
        b.enabled = true;
        b.createdAt = Instant.now();
        b.persist();
        MeetingType tb = new MeetingType();
        tb.ownerId = b.id;
        tb.name = "B Intro";
        tb.slug = "b-intro-busy";
        tb.durationMinutes = 30;
        tb.persist();
        var bStart = day.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC);
        Booking bk = new Booking();
        bk.ownerId = b.id;
        bk.meetingTypeId = tb.id; // B's real type (FK-valid); not under test
        bk.inviteeName = "B";
        bk.inviteeEmail = "b@x.com";
        bk.startUtc = bStart;
        bk.endUtc = bStart.plusSeconds(1800);
        bk.createdAt = Instant.now();
        bk.manageToken = java.util.UUID.randomUUID().toString();
        bk.status = BookingStatus.CONFIRMED;
        bk.persist();

        // availableSlots subtracts the OWNER-SCOPED busy-set (Google free/busy is skipped — not
        // connected — leaving only owner A's HELD bookings, of which there are none). If the busy-set
        // were still instance-wide, B's 10:00 hold would remove A's 10:00 slot. Assert it survives.
        boolean tenAmBookableForA = bookingService.availableSlots(ta, day, day).stream()
                .anyMatch(s -> s.start().toInstant().equals(bStart));
        org.junit.jupiter.api.Assertions.assertTrue(
                tenAmBookableForA, "A's 10:00 slot must stay free — owner B's held booking is not in A's busy-set");
    }

    /**
     * FIX 2 — owner-scoped GLOBAL availability rules: owner A's global rule (meetingTypeId is null)
     * must not affect owner B's slots. B with no rules of their own produces no slots even though A
     * has a wide global rule for the same weekday.
     */
    @Test
    @TestTransaction
    void ownerAGlobalRuleDoesNotAffectOwnerBSlots() {
        AppUser a = AppUser.create("ownera-global", "x", false);
        a.enabled = true;
        a.createdAt = Instant.now();
        a.persist();
        var day = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3);
        AvailabilityRule ga = new AvailabilityRule();
        ga.ownerId = a.id;
        ga.meetingTypeId = null;
        ga.dayOfWeek = day.getDayOfWeek();
        ga.startTime = LocalTime.of(9, 0);
        ga.endTime = LocalTime.of(17, 0);
        ga.persist();

        AppUser b = AppUser.create("ownerb-global", "x", false);
        b.enabled = true;
        b.createdAt = Instant.now();
        b.persist();
        OwnerSettings sb = new OwnerSettings();
        sb.ownerId = b.id;
        sb.ownerName = "B";
        sb.ownerEmail = "b@x.com";
        sb.timezone = "UTC";
        sb.persist();
        MeetingType tb = new MeetingType();
        tb.ownerId = b.id;
        tb.name = "B Intro";
        tb.slug = "b-intro";
        tb.durationMinutes = 30;
        tb.persist();

        // B has NO global rule of their own; A's global rule must not leak into B's resolution.
        assertEquals(
                0,
                slotService.generateRawSlots(tb, day, day).size(),
                "owner B has no availability; owner A's global rule must not apply");
    }

    /** Sanity: the seeded admin login user really is a distinct owner from B. */
    @Test
    @TestTransaction
    void seededOwnersAreDistinct() {
        var ignored = seedOwnerB();
        org.junit.jupiter.api.Assertions.assertNotEquals(
                AppUser.findByUsername("admin").id, AppUser.findByUsername("ownerb").id);
    }
}
