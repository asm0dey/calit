package site.asm0dey.calit.google;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end sign-in: stub the Google network seam, then drive callback -> bridge -> j_security_check
 * and assert a session cookie is minted. Signup enabled so an unknown identity provisions a user.
 */
@QuarkusTest
@TestProfile(GoogleLoginResourceTest.SignupOnProfile.class)
class GoogleLoginResourceTest {

    @Inject
    GoogleOAuthConfig oauthConfig;

    @Inject
    GoogleLoginService loginService; // resolves to the installed mock below (CDI client proxy)

    private static final java.time.Instant FIXED = java.time.Instant.parse("2026-06-12T12:00:00Z");

    /** Stub: returns a fixed identity; inherits the real state issue/validate (same secret). */
    static class StubLoginService extends GoogleLoginService {
        StubLoginService(GoogleOAuthConfig config) { super(config); }
        @Override
        public GoogleIdentity exchangeForIdentity(String code, Instant now) {
            return new GoogleIdentity("sub-e2e", "e2e@example.com", true);
        }
    }

    @BeforeEach
    void installStub() {
        QuarkusMock.installMockForType(new StubLoginService(oauthConfig), GoogleLoginService.class);
        QuarkusMock.installMockForType(
            java.time.Clock.fixed(FIXED, java.time.ZoneOffset.UTC), java.time.Clock.class);
    }

    @Test
    void callbackRendersBridgePostingToSecurityCheck() {
        String state = loginService.issueLoginState(FIXED);
        given().redirects().follow(false)
            .when().get("/api/google/login/callback?code=abc&state=" + state)
            .then().statusCode(200)
            .body(containsString("/j_security_check"))
            .body(containsString("j_password"));
    }

    @Test
    void fullSignInMintsSessionCookie() {
        String state = loginService.issueLoginState(FIXED);
        String html = given().when().get("/api/google/login/callback?code=abc&state=" + state)
                .then().statusCode(200).extract().asString();
        String username = extractValue(html, "j_username");
        String token = extractValue(html, "j_password");

        given().redirects().follow(false)
            .formParam("j_username", username)
            .formParam("j_password", token)
            .when().post("/j_security_check")
            .then().statusCode(302)
            .cookie("quarkus-credential");
    }

    private static String extractValue(String html, String name) {
        String marker = "name=\"" + name + "\" value=\"";
        int i = html.indexOf(marker);
        if (i < 0) throw new AssertionError("missing field " + name + " in: " + html);
        int start = i + marker.length();
        return html.substring(start, html.indexOf('"', start));
    }

    public static class SignupOnProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.signup.enabled", "true");
        }
    }
}
