package site.asm0dey.calit.privacy;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * The "Google is not configured" case. A top-level class rather than a nested one: {@code
 * @TestProfile} triggers an in-JVM Quarkus restart, which JUnit only honors for a class it
 * discovers as a top-level (or {@code @Nested}) test class on the classpath — a plain {@code
 * static class} nested inside another top-level test is invisible to surefire's default
 * inclusion pattern and would silently never run.
 *
 * <p>The override value is a single space, not an empty string: {@link
 * site.asm0dey.calit.google.GoogleOAuthConfig} is a {@code @ConfigMapping} and rejects an empty
 * resolved value for its required {@code oauth.client-id} property (see the comment on {@code
 * %test.google.oauth.client-id} in {@code application.properties}), so the app would fail to boot
 * under this profile with a literal {@code ""}. A single space is non-blank for {@code
 * @ConfigMapping} binding purposes but IS blank under {@link PrivacyFacts}'s {@code
 * String::isBlank} check — the same signal {@code LoginResource} uses to decide Google is
 * unconfigured.
 */
@QuarkusTest
@TestProfile(PrivacyPolicyGoogleUnconfiguredTest.BlankGoogleClientId.class)
class PrivacyPolicyGoogleUnconfiguredTest {
    @Test
    void googleSectionsAreAbsentWhenGoogleIsUnconfigured() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_LEGAL_PRIVACY"))
            .body(not(containsString("Limited Use disclosure")))
            .body(not(containsString("How Google user data is used")));
    }

    /**
     * With Google unconfigured, mail mocked (default {@code %test}), no notification-channel rows,
     * OIDC off ({@code %test} default) and Turnstile off (default), NOTHING sends invitee data to a
     * third party — the fallback sentence must render instead of an empty {@code <ul>}.
     */
    @Test
    void dataSharingFallsBackToTheNoThirdPartySentenceWhenNothingIsConfigured() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("does not send your data to any third party"))
            .body(not(containsString("This deployment sends data to:")));
    }

    public static class BlankGoogleClientId implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("google.oauth.client-id", " ");
        }
    }
}
