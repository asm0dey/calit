package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;

/**
 * /me pages have no #tz-picker, so TZ_SCRIPT used to bail at "if (!picker) return" and leave the
 * raw ISO instant on screen. The script must now format without a picker, using the owner's
 * STORED timezone (not the browser-detected one — a travelling host must not silently read their
 * bookings in the trip's zone).
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
                // pin the fallback ORDER, not just that both operands appear somewhere: RestAssured
                // can't execute the script, so a bare "dataset.tz" check would still pass if the
                // ternary were silently inverted to "(detected || document.body.dataset.tz)" — which
                // would reintroduce the original bug (a travelling host reading their bookings in
                // the trip's timezone instead of their configured one).
                .body(containsString("picker ? picker.value : (document.body.dataset.tz || detected)"));
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
     * Seeds a CONFIRMED booking for the admin owner (id 1) at a fixed instant, so the no-JS
     * fallback text is deterministic across test runs.
     */
    private Long seedConfirmedBooking() {
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
            b.startUtc = Instant.parse("2026-08-20T13:00:00Z");
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
     * the 22:00 local time are stable regardless of when this test runs.
     */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardNoJsFallbackIsHumanReadableWithZone() {
        saveTimezone("Asia/Tokyo");
        seedConfirmedBooking();

        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                // data-utc attribute is untouched -- the client script still keys off it
                .body(containsString("data-utc=\"2026-08-20T13:00:00Z\""))
                // human-rendered fallback carries the zone
                .body(containsString("22:00"))
                .body(containsString("(JST)"))
                // the raw ISO instant is no longer used as the visible fallback text
                .body(not(containsString("2026-08-20T13:00:00Z UTC")));
    }

    /** Same fallback requirement on the pending-approval queue. */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void pendingNoJsFallbackIsHumanReadableWithZone() {
        saveTimezone("Asia/Tokyo");
        // approve() would move it off /me/pending, so seed straight into PENDING instead.
        Long id = QuarkusTransaction.requiringNew().call(() -> {
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
            return b.id;
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
}
