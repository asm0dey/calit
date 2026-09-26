package site.asm0dey.calit.booking;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Default-boot: spy the config bean to hand the challenge endpoint an hmac key, no @TestProfile.
 */
@QuarkusTest
class AltchaChallengeTest {
    @InjectSpy
    CaptchaProviderConfig providerConfig;

    @BeforeEach
    void configureAltcha() {
        when(providerConfig.altchaHmacKey()).thenReturn(Optional.of("test-hmac-secret"));
    }

    @Test
    void challengeEndpointReturnsSignedChallenge() {
        given()
            .when()
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
