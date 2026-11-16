package site.asm0dey.calit.privacy;

import module java.base;
import io.quarkus.test.junit.QuarkusMock;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.web.SiteInfo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * INVITEE_ERASURE=false: the button is replaced by the operator's contact, and the route is gone.
 */
@QuarkusTest
class InviteeErasureDisabledTest {
    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    @BeforeEach
    void erasureOffWithContact() {
        QuarkusMock.installMockForType(new PrivacyConfig(false, Optional.empty()), PrivacyConfig.class);
        QuarkusMock.installMockForType(
                new SiteInfo(Optional.empty(), Optional.empty(), Optional.of("privacy@example.com"), baseUrl),
                SiteInfo.class
        );
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
