package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Username-alias routing for multi-host meeting types: `/{cohost}/{slug}` resolves to the SAME
 * type with the CREATOR driving owner/slot/booking context, and shows a disabled page while a
 * co-host invite is still PENDING. Mirrors BookPageTest's seed style.
 */
@QuarkusTest
class AliasRoutingTest {
    @InjectMock
    CalendarPort calendarPort;

    /**
     * Pasha (creator) + Volodya (co-host), both onboarded, both free 09-17 every weekday.
     */
    @Transactional
    MeetingType seedHosts() {
        AppUser pasha = MultiHostFixtures.enabledUser("pasha");
        AppUser volodya = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.settings(pasha.id, "Pasha Owner");
        MultiHostFixtures.settings(volodya.id, "Volodya Owner");
        for (DayOfWeek dow : DayOfWeek.values()) {
            MultiHostFixtures.rule(pasha.id, dow, 9, 17);
            MultiHostFixtures.rule(volodya.id, dow, 9, 17);
        }
        return MultiHostFixtures.meetingType(pasha.id, "intro", 30);
    }

    /**
     * Volodya is still PENDING — the type is not yet fully bookable.
     */
    @Transactional
    void seedPending() {
        MeetingType t = seedHosts();
        AppUser pasha = AppUser.findByUsername("pasha");
        AppUser volodya = AppUser.findByUsername("volodya");
        MeetingTypeHost.of(t.id, pasha.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, volodya.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING).persist();
    }

    /**
     * Volodya has accepted — the type is fully bookable under both aliases.
     */
    @Transactional
    void seedAccepted() {
        MeetingType t = seedHosts();
        AppUser pasha = AppUser.findByUsername("pasha");
        AppUser volodya = AppUser.findByUsername("volodya");
        MeetingTypeHost.of(t.id, pasha.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, volodya.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
    }

    @Test
    void cohostAliasResolvesToCreatorTypeOnceAccepted() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedAccepted();

        given()
            .when()
            .get("/volodya/intro")
            .then()
            .statusCode(200)
            // booking form rendered, not disabled
            .body(containsString("name=\"startUtc\""))
            .body(not(containsString("CALIT_HOST_PENDING")));
    }

    @Test
    void creatorAliasShowsPendingDisabledMarkerBeforeCohostAccepts() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedPending();

        given()
            .when()
            .get("/pasha/intro")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_HOST_PENDING"))
            .body(not(containsString("name=\"startUtc\"")));
    }

    @Test
    void cohostAliasDoesNotResolveWhileStillPending() {
        // A PENDING co-host isn't recognized as a host of the type at all yet — resolveForAlias
        // only matches ACCEPTED hosts, so the alias route doesn't exist until they accept (404, not
        // the disabled page — that's reserved for URLs that DO resolve to a not-yet-bookable type).
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedPending();

        given().when().get("/volodya/intro").then().statusCode(404);
    }

    @Test
    void cohostLandingListsSharedTypeLinkingToCreatorCanonicalUrlOnceAccepted() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedAccepted();

        given().when().get("/volodya").then().statusCode(200).body(containsString("href=\"/pasha/intro\""));
    }

    @Test
    void cohostLandingOmitsSharedTypeWhileStillPending() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedPending();

        given()
            .when()
            .get("/volodya")
            .then()
            .statusCode(200)
            .body(not(containsString("href=\"/pasha/intro\"")))
            .body(not(containsString("href=\"/volodya/intro\"")));
    }

    /**
     * Pasha (creator) + Volodya (cohost A, ACCEPTED) + Sergey (cohost B, sergeyStatus). A
     * three-host fixture reproducing the Task 15 review finding: an ACCEPTED co-host's own row
     * being accepted is NOT enough — the type is only bookable once EVERY host row is ACCEPTED.
     */
    @Transactional
    MeetingType seedThreeHosts(String sergeyStatus) {
        AppUser pasha = MultiHostFixtures.enabledUser("pasha");
        AppUser volodya = MultiHostFixtures.enabledUser("volodya");
        AppUser sergey = MultiHostFixtures.enabledUser("sergey");
        MultiHostFixtures.settings(pasha.id, "Pasha Owner");
        MultiHostFixtures.settings(volodya.id, "Volodya Owner");
        MultiHostFixtures.settings(sergey.id, "Sergey Owner");
        for (DayOfWeek dow : DayOfWeek.values()) {
            MultiHostFixtures.rule(pasha.id, dow, 9, 17);
            MultiHostFixtures.rule(volodya.id, dow, 9, 17);
            MultiHostFixtures.rule(sergey.id, dow, 9, 17);
        }
        MeetingType t = MultiHostFixtures.meetingType(pasha.id, "trio", 30);
        MeetingTypeHost.of(t.id, pasha.id, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, volodya.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost.of(t.id, sergey.id, MeetingTypeHost.COHOST, sergeyStatus).persist();
        return t;
    }

    @Transactional
    void acceptSergey(MeetingType t) {
        AppUser sergey = AppUser.findByUsername("sergey");
        MeetingTypeHost h = MeetingTypeHost.find(t.id, sergey.id);
        h.status = MeetingTypeHost.ACCEPTED;
        h.persist();
    }

    @Test
    void acceptedCohostLandingHidesTypeWhileADifferentCohostIsStillPending() {
        // Volodya (viewer) is ACCEPTED, but Sergey (a THIRD host) is still PENDING -- the type
        // isn't fully bookable yet, so it must not appear on Volodya's landing even though
        // Volodya's own row is accepted.
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = seedThreeHosts(MeetingTypeHost.PENDING);

        given()
            .when()
            .get("/volodya")
            .then()
            .statusCode(200)
            .body(not(containsString("href=\"/pasha/trio\"")))
            .body(not(containsString("href=\"/volodya/trio\"")));

        acceptSergey(t);

        given().when().get("/volodya").then().statusCode(200).body(containsString("href=\"/pasha/trio\""));
    }

    @Test
    void creatorLandingHidesOwnMultiHostTypeWhileACohostIsStillPending() {
        // The creator's OWN landing must also hide their multi-host type while any co-host
        // (including one who isn't the viewer) is still pending -- not just co-host landings.
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = seedThreeHosts(MeetingTypeHost.PENDING);

        given().when().get("/pasha").then().statusCode(200).body(not(containsString("href=\"/pasha/trio\"")));

        acceptSergey(t);

        given().when().get("/pasha").then().statusCode(200).body(containsString("href=\"/pasha/trio\""));
    }

    @Test
    void postToNotYetBookableMultiHostTypeShowsDisabledPageAndDoesNotCreateBooking() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        seedPending();
        long before = Booking.count();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", Instant.now().plus(1, ChronoUnit.DAYS).toString())
            .formParam("inviteeName", "Guest Invitee")
            .formParam("inviteeEmail", "guest@example.com")
            .formParam("website", "")
            .when()
            .post("/pasha/intro")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_HOST_PENDING"));

        assertEquals(before, Booking.count());
    }
}
