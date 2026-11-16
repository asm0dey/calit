package site.asm0dey.calit.privacy;

import module java.base;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.CaptchaProviderConfig;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The positive SMTP-recipient case: mail is NOT mocked and a real host is configured, so the
 * "Data sharing" list must name it. {@link PrivacyFacts} is swapped for one built with those
 * values; the mailer itself stays mocked, so nothing ever connects to the host.
 */
@QuarkusTest
class PrivacyPolicySmtpConfiguredTest {
    @Inject
    CaptchaProviderConfig captcha;
    @Inject
    PrivacyConfig privacyConfig;

    @BeforeEach
    void realSmtp() {
        QuarkusMock.installMockForType(
                new PrivacyFacts(
                        Optional.of("test-client-id"),
                        false,
                        Optional.of("mail.example.org"),
                        false,
                        captcha,
                        privacyConfig
                ),
                PrivacyFacts.class
        );
    }

    @Test
    void smtpBulletNamesTheConfiguredHostWhenMailIsNotMocked() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("The SMTP server"))
            .body(containsString("mail.example.org"));
    }
}
