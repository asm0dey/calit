package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;

import io.restassured.response.Response;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * Seeds a meeting type + full-week availability for owner 1 (the {@code admin} baseline that
 * {@code DatabaseResetCallback} reseeds before every test) and POSTs one guest booking against it.
 * Extracted from the near-identical inline setup in {@link GuestBookingFlowTest} and
 * {@link BookingPostTest} -- those two classes are left untouched; this is new shared code for
 * tests that just need "a booking happened" without re-deriving the seeding boilerplate.
 */
final class GuestBookingFixture {

    private GuestBookingFixture() {}

    private static final Long OWNER_ID = 1L;

    private static final String OWNER_USERNAME = "admin";

    /** Seeds a meeting type + availability for owner 1 and POSTs one booking. Returns the response. */
    static Response book(String slugSuffix) {
        var slug = "fixture-" + slugSuffix.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        seed(slug);
        var startUtc = firstSlot(slug);
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", startUtc)
                .formParam("inviteeName", "Guest")
                .formParam("inviteeEmail", "guest-" + slug + "@example.com")
                .formParam("website", "") // honeypot left blank (human)
                .when()
                .post("/" + OWNER_USERNAME + "/" + slug);
    }

    // Package-private, not private: ARC does not intercept private (static or instance) methods, so
    // a private @Transactional method here would silently run with no transaction boundary.
    @Transactional
    static void seed(String slug) {
        OwnerSettings s = OwnerSettings.forOwner(OWNER_ID);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = OWNER_ID;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = OWNER_ID;
        t.name = "Fixture Type";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1 555";
        t.persist();

        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = OWNER_ID;
            r.dayOfWeek = d;
            r.startTime = LocalTime.of(0, 0);
            r.endTime = LocalTime.of(23, 59);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    private static String firstSlot(String slug) {
        String html = given().when()
                .get("/" + OWNER_USERNAME + "/" + slug)
                .then()
                .statusCode(200)
                .extract()
                .asString();
        var startUtc =
                html.substring(html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        return startUtc.substring(0, startUtc.indexOf('"'));
    }
}
