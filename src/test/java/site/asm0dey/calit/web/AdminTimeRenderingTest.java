package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

/**
 * Every /me surface shows times in the owner's STORED timezone, not the browser-detected one — a
 * travelling host must not silently read their bookings in the trip's zone.
 *
 * <p>Two shapes are covered. The dashboard and /me/pending have no {@code #tz-picker}: TZ_SCRIPT
 * used to bail at {@code if (!picker) return} and leave the raw ISO instant on screen, and must now
 * format without one. The manage page DOES have a picker, which used to pre-select the detected
 * zone — so the same booking read two different clock times one click apart (calit-mhgs); the
 * picker now starts on the stored zone and is an explicit override.</p>
 *
 * <p>RestAssured cannot execute JS, so these assert on the served HTML and the script text.</p>
 */
@QuarkusTest
class AdminTimeRenderingTest {

    /** Saves a known timezone so the assertion below is deterministic. */
    private void saveTimezone(String zone) {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", zone)
                .formParam("locale", "en")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardCarriesTheOwnersStoredTimezone() {
        saveTimezone("Europe/Amsterdam");

        given().when().get("/me").then().statusCode(200).body(containsString("data-tz=\"Europe/Amsterdam\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void pendingCarriesTheOwnersStoredTimezone() {
        saveTimezone("Asia/Tokyo");

        given().when().get("/me/pending").then().statusCode(200).body(containsString("data-tz=\"Asia/Tokyo\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void scriptNoLongerBailsWhenThereIsNoPicker() {
        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_TZ_REFORMAT"))
                // the early return is gone
                .body(not(containsString("if (!picker) { return; }")))
                // and the no-picker path reads the server-supplied zone
                .body(containsString("document.body.dataset.tz"))
                // The no-picker branch reads the shared `initial`, which is where the fallback now
                // lives -- it used to repeat the whole expression here. The ORDER is still pinned,
                // by thePickerDefaultsToTheZoneThePageWasAuthoredIn below, which asserts on
                // `initial`'s definition character-for-character: RestAssured can't execute the
                // script, so without that a silent inversion to "(detected || dataset.tz)" would
                // reintroduce the original bug (a travelling host reading their bookings in the
                // trip's timezone instead of their configured one).
                .body(containsString("picker ? picker.value : initial"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void thePickerDefaultsToTheZoneThePageWasAuthoredIn() {
        // Asserted against the page the bug was actually on: /me/bookings/{id}/manage is the /me
        // page that HAS a #tz-picker, and the script used to pre-select the browser-detected zone
        // in it -- so the same booking read 15:00 on the dashboard and 22:00 one click away. The
        // picker now defaults to body[data-tz] (the owner's stored zone on /me) and only falls back
        // to detection where there is none, i.e. on the invitee pages.
        saveTimezone("Europe/Amsterdam");
        var bookingId = seedConfirmedBooking(
                LocalDate.now(ZoneOffset.UTC).plusYears(1).atTime(13, 0).toInstant(ZoneOffset.UTC));

        given().when()
                .get("/me/bookings/" + bookingId + "/manage")
                .then()
                .statusCode(200)
                // this page really does render a picker, so the assertions below are about it
                .body(containsString("id=\"tz-picker\""))
                // pin the fallback ORDER, not just that both operands appear somewhere: RestAssured
                // can't execute the script, so a bare "dataset.tz" check would still pass if this
                // were silently inverted to "(detected || document.body.dataset.tz)" -- which would
                // reintroduce the original bug. scriptNoLongerBailsWhenThereIsNoPicker's no-picker
                // branch now reads `initial`, so this is the one place that order is pinned.
                .body(containsString("var initial = document.body.dataset.tz || detected;"))
                .body(containsString("if (z === initial) { o.selected = true; }"))
                // the detected-zone pre-selection is gone
                .body(not(containsString("if (z === detected) { o.selected = true; }")));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardAndPendingNameTheZoneOnScreen() {
        // Whichever zone is displayed must be named: the stored zone is no more self-evident than
        // the detected one, and manageBooking's zone label is the pattern being copied here.
        saveTimezone("Europe/Amsterdam");

        given().when().get("/me").then().statusCode(200).body(containsString("Times shown in Europe/Amsterdam"));

        given().when()
                .get("/me/pending")
                .then()
                .statusCode(200)
                .body(containsString("Times shown in Europe/Amsterdam"));
    }

    /** An explicit host preference reaches the /me pages... */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardCarriesAnExplicitHourCycle() {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("timeFormat", "h23")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("data-hc=\"h23\""))
                // the script prefers the server value over the device probe
                .body(containsString("document.body.dataset.hc"))
                // pin the override PRECEDENCE, not just that the identifier appears somewhere:
                // RestAssured can't execute the script, so a bare "dataset.hc" check would still
                // pass even if the assignment were silently inverted to "HC = HC || forcedHC;" —
                // which would make the device always win and silently ignore the host's stored
                // preference, defeating the whole point of the feature.
                .body(containsString("if (forcedHC) { HC = forcedHC; }"));
    }

    /** ...and "auto" leaves the device in charge, so no cycle is forced. */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void autoEmitsNoForcedHourCycle() {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("timeFormat", "auto")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().when().get("/me").then().statusCode(200).body(containsString("data-hc=\"\""));
    }

    /**
     * Seeds a CONFIRMED booking for the admin owner (id 1) at the given instant. The dashboard
     * filters on {@code startUtc >= Instant.now()}, so callers that need it to still render must
     * pass a future instant.
     */
    private Long seedConfirmedBooking(Instant startUtc) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var slug = "time-render-" + System.nanoTime();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Time Render Type";
            t.slug = slug;
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "No JS Reader";
            b.inviteeEmail = "nojs-reader@example.com";
            b.startUtc = startUtc;
            b.endUtc = b.startUtc.plusSeconds(1800);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.answers = Map.of();
            b.locale = "en";
            b.persist();
            return b.id;
        });
    }

    /**
     * The no-JS fallback (what a JS-off visitor actually reads) must be a human-readable
     * date/time carrying the zone -- not the raw ISO instant. Asia/Tokyo has no DST, so "JST" and
     * the 22:00 local time are stable regardless of when this test runs; the DATE must still be in
     * the future (the dashboard filters {@code startUtc >= Instant.now()}), so it is pinned a year
     * ahead of today rather than to a literal calendar date that would eventually fall into the
     * past and make the dashboard filter it out.
     */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardNoJsFallbackIsHumanReadableWithZone() {
        saveTimezone("Asia/Tokyo");
        var startUtc = LocalDate.now(ZoneOffset.UTC).plusYears(1).atTime(13, 0).toInstant(ZoneOffset.UTC);
        seedConfirmedBooking(startUtc);

        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                // data-utc attribute is untouched -- the client script still keys off it
                .body(containsString("data-utc=\"" + startUtc + "\""))
                // human-rendered fallback carries the zone
                .body(containsString("22:00"))
                .body(containsString("(JST)"))
                // the raw ISO instant is no longer used as the visible fallback text
                .body(not(containsString(startUtc + " UTC")));
    }

    /** Same fallback requirement on the pending-approval queue. */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void pendingNoJsFallbackIsHumanReadableWithZone() {
        saveTimezone("Asia/Tokyo");
        // approve() would move it off /me/pending, so seed straight into PENDING instead.
        QuarkusTransaction.requiringNew().run(() -> {
            var slug = "time-render-pending-" + System.nanoTime();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Time Render Pending Type";
            t.slug = slug;
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.requiresApproval = true;
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "No JS Reader";
            b.inviteeEmail = "nojs-reader-pending@example.com";
            b.startUtc = Instant.parse("2026-08-20T13:00:00Z");
            b.endUtc = b.startUtc.plusSeconds(1800);
            b.status = BookingStatus.PENDING;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.answers = Map.of();
            b.locale = "en";
            b.persist();
        });

        given().when()
                .get("/me/pending")
                .then()
                .statusCode(200)
                .body(containsString("data-utc=\"2026-08-20T13:00:00Z\""))
                .body(containsString("22:00"))
                .body(containsString("(JST)"))
                .body(not(containsString("2026-08-20T13:00:00Z UTC")));
    }

    /**
     * REVIEW FINDING 1: every test above authenticates as admin, and per the test-DB invariant
     * admin is ALWAYS owner id 1. So a mutation that hardcodes {@code OwnerSettings.forOwner(1L)}
     * in {@code AdminResource.ownerZone()} in place of {@code OwnerSettings.forOwner(currentOwner.id())}
     * would pass every test above unchanged -- while silently leaking owner 1's timezone onto
     * every other owner's dashboard and pending page. This pins the ISOLATION property directly:
     * a SECOND owner, with a DIFFERENT stored timezone, must see their OWN zone reflected in the
     * no-JS fallback text -- never owner 1's.
     *
     * <p>{@code body.dataset.tz} (asserted elsewhere in this file) is populated by a separate
     * {@code {inject:owner...}} template global that already reads {@code CurrentOwner} directly,
     * so it cannot exercise a bug confined to {@code ownerZone()}. The only place {@code
     * ownerZone()}'s return value actually reaches the page is the {@code zone} template param fed
     * into {@code display:when(...)} for each rendered booking time -- so this test seeds a booking
     * and asserts on THAT rendered text, exactly like {@code dashboardNoJsFallbackIsHumanReadableWithZone}
     * / {@code pendingNoJsFallbackIsHumanReadableWithZone} above.</p>
     */
    @Test
    @TestSecurity(user = "tz-owner-b", roles = "user")
    void dashboardAndPendingRenderTheAuthenticatedOwnersOwnTimezoneNotOwner1s() {
        seedOwnerOneTimezone("Europe/Amsterdam");
        var ownerBId = seedSecondOwnerWithTimezone("tz-owner-b", "Asia/Tokyo");
        // The dashboard's CONFIRMED booking must stay in the future (startUtc >= Instant.now()),
        // so both days are pinned a year ahead of today rather than to literal calendar dates that
        // would eventually fall into the past. Distinct days keep the two bookings from tripping
        // the DB's same-owner exclusion constraint.
        var day1 = LocalDate.now().plusYears(1);
        seedBookingForOwner(ownerBId, day1, BookingStatus.CONFIRMED, false);
        seedBookingForOwner(ownerBId, day1.plusDays(1), BookingStatus.PENDING, true);

        // Owner B's zone (Asia/Tokyo, no DST -- stable regardless of when this runs) renders the
        // 13:00 UTC start as 22:00 JST. Under the OwnerSettings.forOwner(1L) mutation this would
        // instead render owner 1's Europe/Amsterdam zone (15:00 CEST that same time of year).
        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("22:00"))
                .body(containsString("(JST)"))
                .body(not(containsString("(CEST)")));

        given().when()
                .get("/me/pending")
                .then()
                .statusCode(200)
                .body(containsString("22:00"))
                .body(containsString("(JST)"))
                .body(not(containsString("(CEST)")));
    }

    /**
     * Owner 1 (admin)'s stored zone, set directly rather than via {@code POST /me/settings} --
     * the isolation test above authenticates as owner B for its whole duration and never logs in
     * as admin.
     */
    private void seedOwnerOneTimezone(String zone) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Admin";
            s.ownerEmail = "admin@example.com";
            s.timezone = zone;
            s.persist();
        });
    }

    /**
     * A second, distinct {@link AppUser} with its own {@link OwnerSettings} row -- enabled and
     * onboarded ({@code settingsComplete = true}) so /me and /me/pending serve normally instead
     * of 302-redirecting to the first-login wizard. Returns the new owner's id.
     */
    private long seedSecondOwnerWithTimezone(String username, String zone) {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser b = AppUser.findByUsername(username);
            if (b == null) {
                b = AppUser.create(username, "x", false);
                b.enabled = true;
                b.settingsComplete = true;
                b.persist();
            }

            OwnerSettings s = OwnerSettings.forOwner(b.id);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = b.id;
            }
            s.ownerName = "Owner B";
            s.ownerEmail = username + "@example.com";
            s.timezone = zone;
            s.persist();
            return b.id;
        });
    }

    /**
     * A booking for {@code ownerId} at a fixed instant that renders as 22:00 JST -- same hour used
     * by the human-readable-fallback tests above, so the rendered clock time/zone abbreviation is
     * deterministic. Callers pass distinct days so the two bookings (dashboard's CONFIRMED one and
     * pending's PENDING one) never overlap under the DB's same-owner exclusion constraint.
     */
    private void seedBookingForOwner(long ownerId, LocalDate day, BookingStatus status, boolean requiresApproval) {
        QuarkusTransaction.requiringNew().run(() -> {
            var slug = "time-render-owner-" + ownerId + "-" + status + "-" + System.nanoTime();
            MeetingType t = new MeetingType();
            t.ownerId = ownerId;
            t.name = "Time Render Type";
            t.slug = slug;
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.requiresApproval = requiresApproval;
            t.persist();

            Booking b = new Booking();
            b.ownerId = ownerId;
            b.meetingTypeId = t.id;
            b.inviteeName = "No JS Reader";
            b.inviteeEmail = "nojs-reader-" + System.nanoTime() + "@example.com";
            b.startUtc = day.atTime(13, 0).toInstant(java.time.ZoneOffset.UTC);
            b.endUtc = b.startUtc.plusSeconds(1800);
            b.status = status;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.answers = Map.of();
            b.locale = "en";
            b.persist();
        });
    }
}
