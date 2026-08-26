package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;

@QuarkusTest
class OgTagsTest {

    @Inject
    BookingService bookingService;

    /** Admin is always id 1 / username "admin" (DatabaseResetCallback invariant). */
    private static void seedType(String slug, boolean secret) {
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
            t.secret = secret;
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
    void bookingPageCarriesAbsoluteOgTags() {
        seedType("og-public", false);
        given().when()
                .get("/admin/og-public")
                .then()
                .statusCode(200)
                .body(containsString("property=\"og:title\" content=\"Coffee chat · Ada Lovelace\""))
                .body(containsString("property=\"og:image\" content=\"http://localhost:8080/og/admin/og-public.png\""))
                .body(containsString("property=\"og:url\" content=\"http://localhost:8080/admin/og-public\""))
                .body(containsString("name=\"twitter:card\" content=\"summary_large_image\""))
                .body(containsString("content=\"en_US\""))
                .body(not(containsString("noindex")));
    }

    @Test
    void secretTypeGetsTheGenericCard() {
        seedType("og-secret", true);
        given().when()
                .get("/admin/og-secret")
                .then()
                .statusCode(200)
                .body(not(containsString("Coffee chat · Ada Lovelace")))
                .body(containsString("property=\"og:title\" content=\"calit\""))
                .body(containsString("property=\"og:image\" content=\"http://localhost:8080/og.png\""));
    }

    @Test
    void landingAndProductPagesOptIn() {
        seedType("og-landing", false);
        given().when()
                .get("/admin")
                .then()
                .statusCode(200)
                .body(containsString("property=\"og:title\" content=\"Ada Lovelace · calit\""))
                .body(containsString("property=\"og:image\" content=\"http://localhost:8080/og/admin.png\""));
        given().when().get("/privacy").then().statusCode(200).body(containsString("property=\"og:title\""));
        given().when().get("/login").then().statusCode(200).body(containsString("property=\"og:title\""));
    }

    /**
     * A bogus manage token 404s emptily (no HTML body) rather than rendering base.html, so this test
     * must exercise a REAL booking's Manage hub — the actual capability-URL page {@code /booking/
     * {manageToken}/manage} that renders {@code PublicResource.Templates.manage(...)}, which never
     * receives an {@code OgCard} (Step 7 of the task brief deliberately leaves it untouched).
     */
    @Transactional
    String seedManageToken() {
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
        t.name = "Og Manage Type";
        t.slug = "og-manage";
        t.durationMinutes = 30;
        t.minNoticeMinutes = 0;
        t.horizonDays = 30;
        t.locationType = LocationType.CUSTOM; // avoids any Google Calendar dependency
        t.locationDetail = "Office";
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(0, 0);
            r.endTime = LocalTime.of(23, 59);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService.availableSlots(t, now(), now().plusDays(14)).getFirst();
        return bookingService.book(
                        1L,
                        "og-manage",
                        slot.start().toInstant(),
                        "Pat",
                        "pat@example.com",
                        Map.of(),
                        "",
                        "",
                        "en",
                        List.of())
                .manageToken;
    }

    @Test
    void capabilityUrlsAreNoindexAndCarryNoOgTags() {
        var token = seedManageToken();
        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("name=\"robots\" content=\"noindex,nofollow\""))
                .body(not(containsString("og:title")))
                .body(not(containsString("og:image")));
    }
}
