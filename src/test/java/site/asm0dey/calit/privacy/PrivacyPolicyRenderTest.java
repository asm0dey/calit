package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Renders {@code /privacy} under the default {@code %test} profile, where {@code
 * google.oauth.client-id=test-client-id} is set (see {@code application.properties}), so — per the
 * same signal {@code LoginResource} uses (client id non-blank) — Google IS configured in this
 * profile. The "Google absent" case lives in {@link PrivacyPolicyGoogleUnconfiguredTest}, a
 * separate top-level class, because flipping that one config key requires a distinct Quarkus test
 * profile (an in-JVM restart) rather than a nested test class.
 */
@QuarkusTest
class PrivacyPolicyRenderTest {

    @Test
    void googleSectionsArePresentWhenGoogleIsConfigured() {
        given().when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_LEGAL_PRIVACY"))
                .body(containsString("Limited Use disclosure"));
    }

    @Test
    void deletionClaimIsBackedByTheDeleteRoute() {
        given().when().get("/privacy").then().statusCode(200).body(containsString("/me/settings/delete"));
    }

    @Test
    void retentionSectionSaysForeverWhenUnset() {
        given().when().get("/privacy").then().statusCode(200).body(containsString("CALIT_RETENTION_FOREVER"));
    }

    @Test
    void deletedUsernameHashIsDisclosed() {
        given().when().get("/privacy").then().statusCode(200).body(containsString("can never be re-registered"));
    }

    @Test
    void inviteeErasureScopeIsDisclosed() {
        given().when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("Bookings are not linked to each other by email address"));
    }

    @Test
    void termsPageRendersTheAccountDeletionClaim() {
        given().when()
                .get("/terms")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_LEGAL_TERMS"))
                .body(containsString("/me/settings"))
                .body(containsString("only administrator"));
    }

    /**
     * Mail is mocked under the default {@code %test} profile ({@code quarkus.mailer.mock=true}), so
     * no mail actually leaves the process — even though {@code quarkus.mailer.host} itself resolves
     * to a non-blank {@code @WithDefault("localhost")}. {@link PrivacyFacts#getSmtpHost()} must key
     * off the mock flag, not host blankness, or this bullet would render "localhost" as a recipient
     * in every dev/test environment. The positive case (a real, non-mocked host) lives in {@link
     * PrivacyPolicySmtpConfiguredTest}, a separate top-level class (distinct {@code @TestProfile}).
     */
    @Test
    void smtpBulletIsAbsentWhenMailIsMocked() {
        given().when().get("/privacy").then().statusCode(200).body(not(containsString("The SMTP server")));
    }

    /**
     * OIDC ({@code calit.oidc.enabled=false} under {@code %test}) and Turnstile ({@code
     * calit.turnstile.enabled=false} by default) are both off, and no {@code NotificationChannel}
     * row is seeded — so with Google the only configured recipient, the "does not send to any third
     * party" fallback sentence must NOT render (there IS a recipient: Google).
     */
    @Test
    void dataSharingListRendersWhenAtLeastOneRecipientExists() {
        given().when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("This deployment sends data to:"))
                .body(not(containsString("does not send your data to any third party")));
    }
}
