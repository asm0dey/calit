package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * INVITEE_ERASURE=false: the button is replaced by the operator's contact, and the route is gone.
 */
@QuarkusTest
@TestProfile(InviteeErasureDisabledTest.Off.class)
class InviteeErasureDisabledTest {
    public static class Off implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.privacy.invitee-erasure", "false", "app.privacy-contact", "privacy@example.com");
        }
    }

    @Test
    void manageHubShowsTheOperatorContactInsteadOfTheButton() {
        String token = ErasureFixtures.seedPastBooking();
        given()
            .when()
            .get("/booking/" + token + "/manage")
            .then()
            .statusCode(200)
            .body(containsString("privacy@example.com"))
            .body(not(containsString("/erase")));
    }

    @Test
    void theRouteIsGone() {
        String token = ErasureFixtures.seedPastBooking();
        given().when().get("/booking/" + token + "/erase").then().statusCode(404);
        given().when().post("/booking/" + token + "/erase").then().statusCode(404);
    }
}
