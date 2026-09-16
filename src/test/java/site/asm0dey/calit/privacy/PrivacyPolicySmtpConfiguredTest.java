package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The positive SMTP-recipient case: mail is NOT mocked and a real host is configured, so the
 * "Data sharing" list must name it. Top-level class for the same {@code @TestProfile}-requires-
 * restart reason as its siblings in this package. Only {@code quarkus.mailer.mock} and {@code
 * quarkus.mailer.host} are overridden — quarkus-mailer only opens a connection when actually
 * sending mail, never eagerly at boot, so a non-routable host is safe here (this test never sends
 * anything).
 */
@QuarkusTest
@TestProfile(PrivacyPolicySmtpConfiguredTest.RealSmtp.class)
class PrivacyPolicySmtpConfiguredTest {

    @Test
    void smtpBulletNamesTheConfiguredHostWhenMailIsNotMocked() {
        given().when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("The SMTP server"))
                .body(containsString("mail.example.org"));
    }

    public static class RealSmtp implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.mailer.mock", "false",
                    "quarkus.mailer.host", "mail.example.org");
        }
    }
}
