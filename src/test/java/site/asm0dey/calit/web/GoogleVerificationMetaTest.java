package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CommonFeaturesProfile.class)
class GoogleVerificationMetaTest {
    @Test
    void publicPageRendersVerificationMetaWhenConfigured() {
        given()
            .when()
            .get("/login")
            .then()
            .statusCode(200)
            .body(containsString("name=\"google-site-verification\""))
            .body(containsString("tok_calit_test_123"));
    }
}
