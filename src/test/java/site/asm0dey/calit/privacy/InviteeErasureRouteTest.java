package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * CSRF is disabled in %test, so these POSTs carry no token; production forms must still render
 * {inject:csrf.token} or they 400 (see the global constraints).
 */
@QuarkusTest
class InviteeErasureRouteTest {

    /** Seeds a past booking and returns its manage token. Mirrors BookingErasureTest's fixture. */
    private String seedToken() {
        return ErasureFixtures.seedPastBooking();
    }

    @Test
    void confirmPageStatesTheBoundaryBeforeTheClick() {
        var token = seedToken();
        given().when()
                .get("/booking/" + token + "/erase")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_ERASE_CONFIRM"));
    }

    @Test
    void manageHubOffersErasure() {
        var token = seedToken();
        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/booking/" + token + "/erase"));
    }

    @Test
    void erasingRendersTheDonePageAndDropsTheName() {
        var token = seedToken();
        given().when()
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
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/does-not-exist/erase").then().statusCode(404);
    }
}
