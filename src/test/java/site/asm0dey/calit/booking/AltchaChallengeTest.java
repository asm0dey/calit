package site.asm0dey.calit.booking;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AltchaChallengeTest.AltchaOn.class)
class AltchaChallengeTest {

    public static class AltchaOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.captcha.provider", "altcha",
                    "calit.captcha.altcha.hmac-key", "test-hmac-secret",
                    "calit.captcha.altcha.max-number", "100000");
        }
    }

    @Test
    void challengeEndpointReturnsSignedChallenge() {
        given().when()
                .get("/altcha/challenge")
                .then()
                .statusCode(200)
                .body("algorithm", notNullValue())
                .body("challenge", notNullValue())
                .body("salt", notNullValue())
                .body("signature", notNullValue())
                .body("maxnumber", notNullValue());
    }
}
