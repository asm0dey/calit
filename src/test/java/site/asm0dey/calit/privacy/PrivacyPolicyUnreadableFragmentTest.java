package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.web.IsolatedSettingsProfile;

/**
 * An {@code app.privacy-policy-path} pointing at a file that doesn't exist must never take
 * {@code /privacy} down — the Google consent screen links it. {@link
 * site.asm0dey.calit.web.LegalResource#fragment} catches the read failure, logs, and falls back to
 * the shipped copy. Top-level class for the same {@code @TestProfile}-requires-restart reason as
 * its siblings in this package.
 */
@QuarkusTest
@TestProfile(IsolatedSettingsProfile.class)
class PrivacyPolicyUnreadableFragmentTest {
    @Test
    void anUnreadableOverridePathFallsBackToTheShippedCopy() {
        given().when().get("/privacy").then().statusCode(200).body(containsString("CALIT_LEGAL_PRIVACY"));
    }
}
