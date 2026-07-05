package site.asm0dey.calit.oidc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWireMock;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.filter.cookie.CookieFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;

/**
 * Full SSO bridge: hit /api/oidc/login unauthenticated -> mock provider round-trip ->
 * OidcLoginResource emits the auto-submit bridge form POSTing a ticket to /j_security_check.
 * quarkus-oidc's crypto/discovery/token-exchange is upstream-tested by {@link OidcWiremockTestResource}
 * itself; this test only proves OUR wiring (permission scoping, resource, bridge template).
 *
 * <p>No user is pre-seeded: this proves FIRST-TIME provisioning. {@code EnabledUserAugmentor} passes
 * the transient OIDC identity through untouched (it carries an {@code IdTokenCredential}, so the
 * augmentor's findByUsername enabled-check — which would otherwise anonymize an unknown-username
 * identity — is skipped for it), so an SSO user never seen before still reaches the "authenticated"
 * HTTP permission on /api/oidc/login and {@link OidcLoginResource} provisions a brand-new
 * {@link AppUser} for the mock's sub.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(OidcBridgeFlowTest.OidcOn.class)
class OidcBridgeFlowTest {

    // OidcWiremockTestResource's code-flow stub always issues an id_token for preferred_username
    // "alice" (hardcoded, see the "username=alice" submitted to the mock's login form below) with a
    // fixed sub "123456" (its TOKEN_SUBJECT constant).
    private static final String MOCK_SUB = "123456";

    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // OidcWiremockTestResource.start() only returns "keycloak.url" (the Wiremock server's
            // base "<baseUrl>/auth"); the realm path + client-id/secret are ours to pick — the mock's
            // token/introspection stubs don't validate them, they only echo back whatever client-id
            // is sent via HTTP Basic auth into the id_token's audience, so any value round-trips.
            return Map.of(
                    "calit.oidc.enabled", "true",
                    "quarkus.oidc.tenant-enabled", "true",
                    "calit.signup.enabled", "true",
                    "quarkus.oidc.auth-server-url", "${keycloak.url}/realms/quarkus",
                    "quarkus.oidc.client-id", "quarkus-web-app",
                    "quarkus.oidc.credentials.secret", "secret",
                    "quarkus.oidc.application-type", "web-app");
        }
    }

    // Injected by OidcWiremockTestResource; needed to submit the mock's own login form, which lives
    // on the Wiremock host, not ours.
    @OidcWireMock
    WireMockServer wireMock;

    @Test
    void ssoLogin_bridgesToJSecurityCheck() {
        CookieFilter cookies = new CookieFilter();

        // 1) unauthenticated hit -> quarkus-oidc redirects (302) to the mock provider's authorize
        // endpoint, ALSO setting a state-verification cookie we must carry through the rest of the
        // flow. Do NOT auto-follow here: RestAssured's redirects().follow(true) resolves the whole
        // chain internally and only ever runs the cookie filter on the final response, silently
        // dropping this cookie -- which then makes step 4 look like a fresh, unauthenticated request.
        String authorizeUrl = given().filter(cookies)
                .redirects()
                .follow(false)
                .when()
                .get("/api/oidc/login")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");

        // 2) the mock's authorize endpoint responds 200 with a plain HTML login form (not a redirect),
        // carrying the state/redirect_uri quarkus-oidc generated.
        String loginPage = given().filter(cookies)
                .when()
                .get(authorizeUrl)
                .then()
                .statusCode(200)
                .body(containsString("id=\"login\""))
                .extract()
                .asString();
        var state = between(loginPage, "id=\"state\" name=\"state\" value=\"", "\"");
        var redirectUri = between(loginPage, "id=\"redirect_uri\" name=\"redirect_uri\" value=\"", "\"");

        // 3) submit that form (any credentials; the mock stub authenticates unconditionally) and the
        // mock redirects with a 302 to redirect_uri carrying state and code. The query string is built
        // by hand with URL-encoding disabled: the mock substitutes the redirect_uri query param into
        // its response by raw substring rather than decoding it, so RestAssured's normal percent-encoding
        // would come back double-encoded in the Location header.
        String callbackUrl = given().filter(cookies)
                .urlEncodingEnabled(false)
                .redirects()
                .follow(false)
                .when()
                .get(wireMock.baseUrl() + "/login?state=" + state + "&redirect_uri=" + redirectUri
                        + "&username=alice&password=alice")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");

        // 4) back on our host, with the step-1 state cookie in hand: quarkus-oidc validates state,
        // exchanges the code server-side (token endpoint + id_token verification), mints its own
        // session cookie, expires the state cookie, and -- rather than serving the resource directly
        // -- issues ONE MORE 302 to the same path stripped of ?state=&code= (its usual "clean the
        // address bar" behavior). Do NOT auto-follow: as in step 1, that would run the cookie filter
        // only on the end result, so the fresh session cookie set here would never reach the request
        // that finally hits OidcLoginResource.
        String cleanUrl = given().filter(cookies)
                .redirects()
                .follow(false)
                .when()
                .get(callbackUrl)
                .then()
                .statusCode(302)
                .extract()
                .header("Location");

        // 5) the clean, authenticated hit that actually reaches OidcLoginResource: no AppUser exists
        // yet for this sub, so the resource provisions a brand-new one (calit.signup.enabled=true in
        // OidcOn) and returns the bridge page.
        given().filter(cookies)
                .when()
                .get(cleanUrl)
                .then()
                .statusCode(200)
                .body(containsString("action=\"/j_security_check\""))
                .body(containsString("name=\"j_password\"")); // the single-use ticket

        assertNotNull(AppUser.findByOidcSub(MOCK_SUB), "expected first-time SSO login to provision a new AppUser");
    }

    private static String between(String s, String start, String end) {
        var i = s.indexOf(start);
        assertTrue(i >= 0, () -> "missing '" + start + "' in: " + s);
        var from = i + start.length();
        return s.substring(from, s.indexOf(end, from));
    }
}
