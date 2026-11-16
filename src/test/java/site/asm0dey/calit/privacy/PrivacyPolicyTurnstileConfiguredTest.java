package site.asm0dey.calit.privacy;

import module java.base;
import static org.mockito.Mockito.when;
import io.quarkus.test.InjectMock;
import org.junit.jupiter.api.BeforeEach;
import site.asm0dey.calit.booking.CaptchaProviderConfig;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * When Cloudflare Turnstile is the effective CAPTCHA provider ({@link
 * site.asm0dey.calit.booking.CaptchaProviderConfig#provider()} resolves to {@code "turnstile"}),
 * the "Data sharing" list must disclose it.
 */
@QuarkusTest
class PrivacyPolicyTurnstileConfiguredTest {
    @InjectMock
    CaptchaProviderConfig captcha;

    @BeforeEach
    void turnstileOn() {
        when(captcha.provider()).thenReturn("turnstile");
    }

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
