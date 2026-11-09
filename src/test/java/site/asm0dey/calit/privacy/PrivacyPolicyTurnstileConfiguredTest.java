package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.web.IsolatedSettingsProfile;

/**
 * When Cloudflare Turnstile is the effective CAPTCHA provider ({@link
 * site.asm0dey.calit.booking.CaptchaProviderConfig#provider()} resolves to {@code "turnstile"}),
 * the "Data sharing" list must disclose it. Top-level class for the same
 * {@code @TestProfile}-requires-restart reason as its siblings in this package.
 */
@QuarkusTest
@TestProfile(IsolatedSettingsProfile.class)
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
}
