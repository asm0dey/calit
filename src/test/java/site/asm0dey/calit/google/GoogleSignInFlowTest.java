package site.asm0dey.calit.google;

import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.filter.cookie.CookieFilter;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end Google sign-in proving the two end-user journeys:
 *  1. A returning, already-onboarded user linked to Google lands on /me (dashboard), NOT the wizard.
 *  2. A freshly provisioned Google user is bounced to /me/setup, with the email pre-filled.
 *
 * Signup is enabled so unknown identities provision. Seed data is committed via
 * QuarkusTransaction.requiringNew so the separate HTTP-request transaction can see it.
 */
@QuarkusTest
@TestProfile(GoogleSignInFlowTest.SignupOnProfile.class)
class GoogleSignInFlowTest {

    @Inject
    GoogleOAuthConfig oauthConfig;

    @Inject
    GoogleLoginService loginService; // resolves to the installed mock below (CDI client proxy)

    private static final java.time.Instant FIXED = java.time.Instant.parse("2026-06-12T12:00:00Z");

    @BeforeEach
    void freezeClock() {
        QuarkusMock.installMockForType(
            java.time.Clock.fixed(FIXED, java.time.ZoneOffset.UTC), java.time.Clock.class);
    }

    /** Stub returning a chosen identity; inherits the real state issue/validate. */
    static class StubFor extends GoogleLoginService {
        private final GoogleIdentity identity;
        StubFor(GoogleOAuthConfig c, GoogleIdentity identity) { super(c); this.identity = identity; }
        @Override public GoogleIdentity exchangeForIdentity(String code, Instant now) { return identity; }
    }

    private void stubIdentity(GoogleIdentity id) {
        QuarkusMock.installMockForType(new StubFor(oauthConfig, id), GoogleLoginService.class);
    }

    /** Seed a Google-linked, already-onboarded user with a committed OwnerSettings row. */
    private void seedOnboardedLinkedUser() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.createGoogleUser("returning", "sub-returning");
            u.settingsComplete = true; // already onboarded
            u.persist();
            OwnerSettings s = new OwnerSettings();
            s.ownerId = u.id;
            s.ownerName = "Ret";
            s.ownerEmail = "ret@x.com";
            s.timezone = "UTC";
            s.persist();
        });
    }

    /** Drive callback -> bridge -> j_security_check within one session, minting the session cookie. */
    private void signIn(CookieFilter session) {
        String state = loginService.issueLoginState(FIXED);
        String html = given().filter(session)
                .when().get("/api/google/login/callback?code=c&state=" + state)
                .then().statusCode(200).extract().asString();
        String username = between(html, "name=\"j_username\" value=\"", "\"");
        String token = between(html, "name=\"j_password\" value=\"", "\"");
        given().filter(session).redirects().follow(false)
                .formParam("j_username", username).formParam("j_password", token)
                .when().post("/j_security_check")
                .then().statusCode(302);
    }

    @Test
    void returningLinkedUserReachesDashboard() {
        seedOnboardedLinkedUser();
        stubIdentity(new GoogleIdentity("sub-returning", "ret@x.com", true));
        CookieFilter session = new CookieFilter();
        signIn(session);
        // Onboarded user: /me loads (200), NOT redirected to /me/setup.
        given().filter(session).redirects().follow(false)
            .when().get("/me")
            .then().statusCode(200);
    }

    @Test
    void newGoogleUserIsSentToOnboardingWizardWithPrefilledEmail() {
        stubIdentity(new GoogleIdentity("sub-fresh", "fresh.person@x.com", true));
        CookieFilter session = new CookieFilter();
        signIn(session);
        // Freshly provisioned (settingsComplete=false) -> /me bounces to /me/setup.
        given().filter(session).redirects().follow(false)
            .when().get("/me")
            .then().statusCode(302).header("Location", containsString("/me/setup"));
        // Wizard pre-fills the Google email from the pre-created OwnerSettings row.
        given().filter(session)
            .when().get("/me/setup")
            .then().statusCode(200).body(containsString("fresh.person@x.com"));
    }

    private static String between(String s, String start, String end) {
        int i = s.indexOf(start);
        if (i < 0) throw new AssertionError("missing '" + start + "' in: " + s);
        int from = i + start.length();
        return s.substring(from, s.indexOf(end, from));
    }

    public static class SignupOnProfile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("calit.signup.enabled", "true");
        }
    }
}
