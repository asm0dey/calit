package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When Cloudflare Turnstile is the effective CAPTCHA provider ({@link
 * site.asm0dey.calit.booking.CaptchaProviderConfig#provider()} resolves to {@code "turnstile"}),
 * the "Data sharing" list must disclose it. Top-level class for the same
 * {@code @TestProfile}-requires-restart reason as its siblings in this package.
 */
@QuarkusTest
@TestProfile(PrivacyPolicyTurnstileConfiguredTest.TurnstileOn.class)
class PrivacyPolicyTurnstileConfiguredTest {
    @Test
    void turnstileBulletRendersWhenTurnstileIsTheEffectiveCaptchaProvider() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("Cloudflare Turnstile, which checks booking requests for abuse."));
    }

    public static class TurnstileOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.turnstile.enabled", "true");
        }
    }
}
