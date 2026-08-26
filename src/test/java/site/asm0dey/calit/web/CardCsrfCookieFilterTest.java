package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.time.LocalTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * calit-7hls: the social-preview card image endpoints ({@code /og*.png}, {@link OgImageResource})
 * are declared {@code Cache-Control: public, max-age=3600} -- explicitly designed for a shared
 * cache/CDN to store -- and are fetched by unfurl crawlers with no session at all. quarkus-rest-csrf
 * mints a {@code csrf-token} Set-Cookie on every safe GET regardless of content type, so those
 * responses were simultaneously cacheable AND carrying a per-visitor cookie: a shared cache could
 * serve one visitor's csrf token to another. {@link CardCsrfCookieFilter} strips that cookie on the
 * card paths only.
 *
 * <p>The critical companion assertion is {@link #bookingPageStillGetsCsrfCookieAndToken()}: the fix
 * must not touch the one real consumer of the cookie, the booking form at {@code /{user}/{slug}}
 * ({@code book.html}'s {@code {inject:csrf.token}} hidden field). A fix that silently disarmed CSRF
 * there would be worse than the bug being fixed.
 *
 * <p>{@code quarkus.rest-csrf} is disabled by default in {@code %test} (~89 existing tokenless
 * form-POST RestAssured sites rely on that -- see {@code application.properties}). This profile
 * re-enables it, exactly like {@link CsrfEnforcementTest}, so the real
 * {@code CsrfRequestResponseReactiveFilter} actually runs here; without it this test would pass
 * vacuously (no cookie is ever minted when the extension is off) regardless of whether the fix
 * exists.
 */
@QuarkusTest
@TestProfile(CsrfEnforcementTest.CsrfOn.class)
class CardCsrfCookieFilterTest {

    static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    /** Admin is always id 1 / username "admin" (DatabaseResetCallback invariant). */
    private static void seedBookableType(String slug) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Ada Lovelace";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Coffee chat";
            t.slug = slug;
            t.durationMinutes = 30;
            t.minNoticeMinutes = 0;
            t.horizonDays = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.persist();
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = java.time.LocalDate.now().getDayOfWeek();
            r.meetingTypeId = null;
            r.startTime = LocalTime.parse("00:00");
            r.endTime = LocalTime.parse("23:59");
            r.persist();
        });
    }

    @Test
    void productCardHasNoCsrfCookie() {
        Response r =
                given().when().get("/og.png").then().statusCode(200).extract().response();
        assertNull(r.getDetailedCookie("csrf-token"), "card image must not carry a csrf-token cookie");
        assertEquals("public, max-age=3600", r.header("Cache-Control"));
        assertNotNull(r.header("ETag"), "card image must still carry its ETag");
        assertArrayEquals(PNG_MAGIC, Arrays.copyOf(r.asByteArray(), 4));
    }

    @Test
    void ownerCardHasNoCsrfCookie() {
        Response r = given().when()
                .get("/og/admin.png")
                .then()
                .statusCode(200)
                .extract()
                .response();
        assertNull(r.getDetailedCookie("csrf-token"), "card image must not carry a csrf-token cookie");
        assertEquals("public, max-age=3600", r.header("Cache-Control"));
        assertNotNull(r.header("ETag"), "card image must still carry its ETag");
    }

    @Test
    void meetingTypeCardHasNoCsrfCookie() {
        seedBookableType("csrf-card-check");
        Response r = given().when()
                .get("/og/admin/csrf-card-check.png")
                .then()
                .statusCode(200)
                .extract()
                .response();
        assertNull(r.getDetailedCookie("csrf-token"), "card image must not carry a csrf-token cookie");
        assertEquals("public, max-age=3600", r.header("Cache-Control"));
        assertNotNull(r.header("ETag"), "card image must still carry its ETag");
    }

    @Test
    void bookingPageStillGetsCsrfCookieAndToken() {
        seedBookableType("csrf-booking-check");
        Response r = given().when()
                .get("/admin/csrf-booking-check")
                .then()
                .statusCode(200)
                .extract()
                .response();
        String token = r.getCookie("csrf-token");
        assertNotNull(token, "booking page GET must still set the csrf-token cookie");
        assertTrue(
                r.asString().contains("name=\"csrf-token\" value=\"" + token + "\""),
                "booking form must still render the matching hidden csrf token field");
    }
}
