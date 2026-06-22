package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(GoogleVerificationMetaTest.WithToken.class)
class GoogleVerificationMetaTest {

    public static class WithToken implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.google-site-verification", "tok_calit_test_123");
        }
    }

    @Test
    void publicPageRendersVerificationMetaWhenConfigured() {
        given()
            .when().get("/login")
            .then()
            .statusCode(200)
            .body(containsString("name=\"google-site-verification\""))
            .body(containsString("tok_calit_test_123"));
    }
}
