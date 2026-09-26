package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * CSRF is disabled in %test, so these POSTs carry no token; production forms must still render
 * {inject:csrf.token} or they 400 (see the global constraints).
 */
@QuarkusTest
class InviteeErasureRouteTest {
    /**
     * Seeds a past booking and returns its manage token. Mirrors BookingErasureTest's fixture.
     */
    private String seedToken() {
        return ErasureFixtures.seedPastBooking();
    }

    @Test
    void confirmPageStatesTheBoundaryBeforeTheClick() {
        var token = seedToken();
        given()
            .when()
            .get("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_ERASE_CONFIRM"));
    }

    @Test
    void manageHubOffersErasure() {
        var token = seedToken();
        given()
            .when()
            .get("/booking/" + token + "/manage")
            .then()
            .statusCode(200)
            .body(containsString("/booking/" + token + "/erase"));
    }

    @Test
    void erasingRendersTheDonePageAndDropsTheName() {
        var token = seedToken();
        given()
            .when()
            .post("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_ERASED"))
            .body(not(containsString("Dana Vogel")));
    }

    @Test
    void everyInviteeRouteIs404AfterErasure() {
        var token = seedToken();
        given().when().post("/booking/" + token + "/erase").then().statusCode(200);

        for (String path : new String[] {"/manage", "/invite.ics", "/cancel", "/erase"}) {
            given().when().get("/booking/" + token + path).then().statusCode(404);
        }
        // The mutating POST routes too — a stale tab or a crafted POST must 404, not silently
        // act on (or re-erase) a booking whose data is already gone.
        for (String path : new String[] {"/cancel", "/reschedule", "/edit-details", "/erase"}) {
            given().when().post("/booking/" + token + path).then().statusCode(404);
        }
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/does-not-exist/erase").then().statusCode(404);
    }

    /**
     * Task 9b: with no instance-wide retention default and no owner override (the fixture's
     * plain {@link ErasureFixtures#seedPastBooking()} baseline), the confirm page must tell the
     * invitee their OTHER bookings' manage links keep working forever, not name a day count.
     */
    @Test
    void confirmPageSaysLinksKeepWorkingForeverByDefault() {
        var token = seedToken();
        given()
            .when()
            .get("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_ERASE_NOT_LINKED"))
            .body(containsString("keep working until"))
            .body(not(containsString("stop working")));
    }

    /**
     * Owner 1 with a 30-day retention override: the confirm page must name that window instead of
     * the "keep forever" copy.
     */
    @Test
    void confirmPageStatesTheOwnerRetentionWindow() {
        var token = seedToken();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(ErasureFixtures.OWNER);
            s.bookingRetentionDays = 30;
        });

        given()
            .when()
            .get("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_ERASE_NOT_LINKED"))
            .body(containsString("stop working 30 days"));
    }

    /**
     * Task 11b: the erasure copy ships in Hebrew, including the {days} placeholder.
     */
    @Test
    void confirmPageRendersInHebrew() {
        var token = seedToken();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(ErasureFixtures.OWNER);
            s.bookingRetentionDays = 30;
        });

        given()
            .header("Accept-Language", "he")
            .when()
            .get("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("למחוק את הנתונים שלך מהזמנה זו?"))
            .body(containsString("מפסיקים לעבוד 30 ימים"))
            .body(not(containsString("Erase your data from this booking?")));
    }

    @Test
    void donePageAlsoStatesTheNotLinkedBoundary() {
        var token = seedToken();
        given()
            .when()
            .post("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_ERASE_NOT_LINKED"));
    }

    /**
     * Fix round 1: an owner override written before {@link PrivacyConfig#MAX_RETENTION_DAYS}
     * existed (or by anything other than the clamping settings form) must still be reported as
     * the clamped value — {@code RetentionScheduler}'s SQL sweep applies the same cap, so the page
     * must never promise a longer window than the sweep will actually honour.
     */
    @Test
    void confirmPageClampsAnOversizedOwnerOverride() {
        var token = seedToken();
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(ErasureFixtures.OWNER);
            s.bookingRetentionDays = 99_999_999;
        });

        given()
            .when()
            .get("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_ERASE_NOT_LINKED"))
            .body(containsString("stop working " + PrivacyConfig.MAX_RETENTION_DAYS + " days"));
    }

    /**
     * Fix round 1 (promoted minor): R22's LEFT JOIN means the retention sweep never skips an owner
     * with no {@code owner_settings} row — the erase confirm page must be equally robust and fall
     * back to "keep forever" rather than 404 or NPE.
     */
    @Test
    void confirmPageFallsBackToForeverWithNoOwnerSettingsRow() {
        var token = seedToken();
        QuarkusTransaction
            .requiringNew()
            .run(() -> OwnerSettings.delete("ownerId", ErasureFixtures.OWNER));

        given()
            .when()
            .get("/booking/" + token + "/erase")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_ERASE_NOT_LINKED"))
            .body(containsString("keep working until"));
    }
}
