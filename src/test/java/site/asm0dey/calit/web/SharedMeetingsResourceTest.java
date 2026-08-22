package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import site.asm0dey.calit.domain.*;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Co-host's own dashboard-consent view and shared-type availability editor at {@code
 * /me/shared/...} ({@link SharedMeetingsResource}). Every scenario seeds a multi-host type OWNED
 * BY A DIFFERENT USER with admin (owner id 1) as co-host — the owner-scoping invariant under test
 * is that every read/write here is scoped to the CALLER's own owner_id, never the creator's.
 * {@link CohostAvailabilityAuthzTest} already covers authorization + crafted-input validation on
 * the availability editor; this class covers the still-untested handlers: the dashboard
 * accept/decline, the availability/override/buffer happy paths, and self-revoke.
 */
@QuarkusTest
class SharedMeetingsResourceTest {

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

    @Transactional
    AppUser seedOtherOwner(String username) {
        AppUser u = MultiHostFixtures.enabledUser(username);
        MultiHostFixtures.settings(u.id, username);
        return u;
    }

    /** Admin (id 1) is a PENDING co-host of a type created by a different owner. */
    @Transactional
    MeetingType seedAdminPendingCohost(String slug) {
        AppUser creator = seedOtherOwner("pending-creator-" + System.nanoTime());
        MeetingType t = MultiHostFixtures.meetingType(creator.id, slug, 30);
        MeetingTypeHost.of(t.id, creator.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING)
                .persist();
        return t;
    }

    /** Admin (id 1) is an ACCEPTED co-host of a type created by a different owner. */
    @Transactional
    MeetingType seedAdminAcceptedCohost(String slug) {
        AppUser creator = seedOtherOwner("acc-creator-" + System.nanoTime());
        return MultiHostFixtures.acceptedTwoHostType(creator.id, 1L, slug, 30, false);
    }

    record Seeded(MeetingType type, Long creatorId) {}

    /** Admin (id 1) is an ACCEPTED co-host, both hosts bookable Monday, for the revoke/booking tests. */
    @Transactional
    Seeded seedBookableAdminAcceptedCohost(String slug) {
        AppUser creator = seedOtherOwner("book-creator-" + System.nanoTime());
        MultiHostFixtures.settings(1L, "admin-1");
        MultiHostFixtures.rule(creator.id, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MeetingType t = MultiHostFixtures.acceptedTwoHostType(creator.id, 1L, slug, 60, false);
        return new Seeded(t, creator.id);
    }

    private void stubOrganizerOnCreator(Long creatorId) {
        when(calendarPort.isConnected(creatorId)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && !id.equals(creatorId))))
                .thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt", "meet", "cal", null));
    }

    // ---- Dashboard consent: GET /me/shared/requests ----

    @Test
    void requestsShowsPendingInviteWithAcceptAndDeclineForms() {
        MeetingType t = seedAdminPendingCohost("dash-pending-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared/requests")
                .then()
                .statusCode(200)
                .body(containsString(t.name))
                .body(containsString("action=\"/me/shared/requests/" + t.id + "/accept\""))
                .body(containsString("action=\"/me/shared/requests/" + t.id + "/decline\""));
    }

    @Test
    void requestsIsEmptyWhenNoPendingInvites() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared/requests")
                .then()
                .statusCode(200)
                .body(not(containsString("/accept\"")));
    }

    // ---- Dashboard consent: POST accept / decline ----

    @Test
    void acceptFlipsCallersOwnPendingRowToAccepted() {
        MeetingType t = seedAdminPendingCohost("dash-accept-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/requests/" + t.id + "/accept")
                .then()
                .statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(t.id, 1L);
        assertNotNull(h, "row must still exist after accept");
        assertEquals(MeetingTypeHost.ACCEPTED, h.status, "caller's own pending row must flip to ACCEPTED");
    }

    @Test
    void acceptOnTypeCallerIsNotPendingHostOfReturns404() {
        AppUser creator = seedOtherOwner("not-pending-creator-" + System.nanoTime());
        MeetingType t = seedAdminPendingCohost("dash-accept-scope-" + System.nanoTime());
        // A second, unrelated type admin has no row on at all.
        MeetingType unrelated = seedTypeOwnedBy(creator.id, "dash-accept-unrelated-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/requests/" + unrelated.id + "/accept")
                .then()
                .statusCode(404);

        // sanity: the real pending row for `t` is untouched by the 404 attempt on `unrelated`
        MeetingTypeHost h = MeetingTypeHost.find(t.id, 1L);
        assertEquals(MeetingTypeHost.PENDING, h.status);
    }

    @Transactional
    MeetingType seedTypeOwnedBy(long ownerId, String slug) {
        return MultiHostFixtures.meetingType(ownerId, slug, 30);
    }

    @Test
    void declineRemovesCallersOwnPendingRow() {
        MeetingType t = seedAdminPendingCohost("dash-decline-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/requests/" + t.id + "/decline")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(t.id, 1L), "declined row must be removed");
    }

    @Test
    void declineOnTypeCallerHasNoPendingRowForReturns404() {
        AppUser creator = seedOtherOwner("not-pending-creator2-" + System.nanoTime());
        MeetingType unrelated = seedTypeOwnedBy(creator.id, "dash-decline-scope-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/requests/" + unrelated.id + "/decline")
                .then()
                .statusCode(404);
    }

    // ---- Availability editor: GET happy path + PENDING-host 404 ----

    @Test
    void availabilityEditorRendersForAcceptedCohost() {
        MeetingType t = seedAdminAcceptedCohost("avail-get-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared/" + t.id + "/availability")
                .then()
                .statusCode(200)
                .body(containsString(t.name));
    }

    @Test
    void availabilityEditorReturns404ForPendingCohost() {
        MeetingType t = seedAdminPendingCohost("avail-pending-get-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared/" + t.id + "/availability")
                .then()
                .statusCode(404);
    }

    // ---- Availability bulk save ----

    @Test
    void bulkSavePersistsRulesUnderCallersOwnOwnerId() {
        MeetingType t = seedAdminAcceptedCohost("avail-bulk-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY")
                .formParam("frameStart", "09:00")
                .formParam("frameEnd", "17:00")
                .when()
                .post("/me/shared/" + t.id + "/availability/bulk")
                .then()
                .statusCode(200);

        List<AvailabilityRule> rules = AvailabilityRule.list("ownerId = ?1 and meetingTypeId = ?2", 1L, t.id);
        assertEquals(1, rules.size(), "rule must be persisted under admin's own owner_id + this typeId");
        assertEquals(DayOfWeek.MONDAY, rules.get(0).dayOfWeek);
    }

    // ---- Date overrides: add + delete ----

    @Test
    void addOverridePersistsOverrideAndWindowsUnderCallersOwnOwnerId() {
        MeetingType t = seedAdminAcceptedCohost("override-add-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("date", "2030-01-15")
                .formParam("windowStart", "10:00")
                .formParam("windowEnd", "12:00")
                .when()
                .post("/me/shared/" + t.id + "/date-overrides")
                .then()
                .statusCode(200);

        DateOverride o = DateOverride.find("ownerId = ?1 and meetingTypeId = ?2", 1L, t.id)
                .firstResult();
        assertNotNull(o, "override must be persisted under admin's own owner_id + this typeId");
        assertEquals(LocalDate.parse("2030-01-15"), o.overrideDate);
        List<DateOverrideWindow> windows = DateOverrideWindow.list("dateOverrideId", o.id);
        assertEquals(1, windows.size());
    }

    @Test
    void deleteOverrideRemovesCallersOwnOverrideAndWindows() {
        MeetingType t = seedAdminAcceptedCohost("override-delete-" + System.nanoTime());
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("date", "2030-02-20")
                .formParam("windowStart", "09:00")
                .formParam("windowEnd", "11:00")
                .when()
                .post("/me/shared/" + t.id + "/date-overrides")
                .then()
                .statusCode(200);
        DateOverride o = DateOverride.find("ownerId = ?1 and meetingTypeId = ?2", 1L, t.id)
                .firstResult();
        assertNotNull(o);

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/" + t.id + "/date-overrides/" + o.id + "/delete")
                .then()
                .statusCode(200);

        // Note: a count query (not findById) -- the entity was already loaded into this test
        // thread's persistence context above, so findById would return the stale JPA-identity-mapped
        // instance instead of re-querying (JPA's EntityManager.find semantics never re-hit the DB
        // for an already-managed id, even though the row was deleted by a separate HTTP request).
        assertEquals(0, DateOverride.count("id = ?1", o.id), "override row must be gone from the DB");
        assertEquals(0, DateOverrideWindow.count("dateOverrideId", o.id), "windows must be gone too");
    }

    /** deleteOverride's owner-scope guard: admin cannot delete another host's override on the same type. */
    @Test
    void deleteOverrideCannotRemoveAnotherOwnersOverrideOnSameType() {
        AppUser creator = seedOtherOwner("override-owner-creator-" + System.nanoTime());
        MeetingType t = seedAcceptedTwoHostType(creator.id, 1L, "override-scope-" + System.nanoTime());
        DateOverride other = seedOverrideForOwner(creator.id, t.id, LocalDate.parse("2030-03-10"));

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/" + t.id + "/date-overrides/" + other.id + "/delete")
                .then()
                .statusCode(200); // requireAcceptedHost passes (admin IS an accepted host of this type)

        assertEquals(
                1, DateOverride.count("id = ?1", other.id), "another owner's override must survive the delete attempt");
    }

    @Transactional
    MeetingType seedAcceptedTwoHostType(long creatorId, long cohostId, String slug) {
        return MultiHostFixtures.acceptedTwoHostType(creatorId, cohostId, slug, 30, false);
    }

    @Transactional
    DateOverride seedOverrideForOwner(long ownerId, Long typeId, LocalDate date) {
        DateOverride o = new DateOverride();
        o.ownerId = ownerId;
        o.meetingTypeId = typeId;
        o.overrideDate = date;
        o.persist();
        return o;
    }

    // ---- Buffers ----

    @Test
    void saveBuffersSetsCallersOwnHostRow() {
        MeetingType t = seedAdminAcceptedCohost("buffers-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("bufferBeforeMinutes", "15")
                .formParam("bufferAfterMinutes", "20")
                .when()
                .post("/me/shared/" + t.id + "/buffers")
                .then()
                .statusCode(200);

        MeetingTypeHost h = MeetingTypeHost.find(t.id, 1L);
        assertEquals(15, h.bufferBeforeMinutes);
        assertEquals(20, h.bufferAfterMinutes);
    }

    // ---- Self-revoke ----

    @Test
    void revokeWithNoFutureBookingsRemovesHostImmediately() {
        var seeded = seedBookableAdminAcceptedCohost("revoke-none-" + System.nanoTime());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/" + seeded.type().id + "/revoke")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(seeded.type().id, 1L), "co-host row removed immediately");
    }

    @Test
    void revokeWithFutureBookingRendersInterstitialAndDoesNotRemoveHost() {
        var seeded = seedBookableAdminAcceptedCohost("revoke-confirm-" + System.nanoTime());
        stubOrganizerOnCreator(seeded.creatorId());
        bookingService.book(
                seeded.creatorId(),
                seeded.type().slug,
                nextMonday(10),
                "Sam",
                "sam@x.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/" + seeded.type().id + "/revoke")
                .then()
                .statusCode(200)
                // the count-agnostic wording, asserted in full: a bare containsString("1") stayed
                // green through the rewording and would stay green through the next one too.
                .body(containsString("Upcoming bookings for this shared meeting type: 1"))
                .body(containsString("choice=keep"))
                .body(containsString("choice=cancel"));

        assertNotNull(MeetingTypeHost.find(seeded.type().id, 1L), "co-host row must survive the interstitial");
    }

    @Test
    void revokeChoiceKeepRemovesHostButLeavesBookingConfirmed() {
        var seeded = seedBookableAdminAcceptedCohost("revoke-keep-" + System.nanoTime());
        stubOrganizerOnCreator(seeded.creatorId());
        Booking lead = bookingService.book(
                seeded.creatorId(),
                seeded.type().slug,
                nextMonday(10),
                "Sam",
                "sam@x.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/" + seeded.type().id + "/revoke?choice=keep")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(seeded.type().id, 1L), "co-host row removed on keep");
        for (Booking row : Booking.<Booking>group(lead.groupId)) {
            assertEquals(BookingStatus.CONFIRMED, row.status, "existing booking honored on keep");
        }
    }

    @Test
    void revokeChoiceCancelCancelsGroupAndRemovesHost() {
        var seeded = seedBookableAdminAcceptedCohost("revoke-cancel-" + System.nanoTime());
        stubOrganizerOnCreator(seeded.creatorId());
        Booking lead = bookingService.book(
                seeded.creatorId(),
                seeded.type().slug,
                nextMonday(10),
                "Sam",
                "sam@x.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of());

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/shared/" + seeded.type().id + "/revoke?choice=cancel")
                .then()
                .statusCode(200);

        assertNull(MeetingTypeHost.find(seeded.type().id, 1L), "co-host row removed on cancel");
        for (Booking row : Booking.<Booking>group(lead.groupId)) {
            assertEquals(BookingStatus.CANCELLED, row.status, "group booking cancelled");
        }
    }
}
