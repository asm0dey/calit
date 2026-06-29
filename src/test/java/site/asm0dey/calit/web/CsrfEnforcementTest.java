package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SEC-SECRET-04: proves the REST CSRF filter actually enforces tokens. The extension is disabled in
 * %test by default (so the ~89 existing tokenless form-POST sites need no plumbing); this profile
 * re-enables it with default paths — every GET mints the csrf-token cookie and every form POST must
 * carry it — asserting: tokenless form POST -> 400, and a tokened POST (cookie == form field)
 * succeeds. Enabling the real extension also excludes the %test CsrfTestStub (gated on enabled=false),
 * so these assertions exercise the genuine filter and provider.
 *
 * <p>The authenticated cases use a real form-login cookie ({@link FormAuth#login()}) rather than
 * {@code @TestSecurity}, mirroring {@link AdminSettingsTest}: the seeded admin (id 1) is onboarded
 * (settingsComplete=true), so {@code MeOwnerFilter} resolves {@code CurrentOwner} and GET/POST
 * {@code /me/settings} return 200 without a /me/setup redirect. {@code /j_security_check} is handled
 * by the form-auth mechanism (not the JAX-RS CSRF filter), so login itself needs no token.
 */
@QuarkusTest
@TestProfile(CsrfEnforcementTest.CsrfOn.class)
class CsrfEnforcementTest {

    public static class CsrfOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.rest-csrf.enabled", "true");
        }
    }

    // Enforcement: an authenticated state-changing form POST with no CSRF token is rejected (400).
    @Test
    void tokenlessAuthenticatedPostIsRejected() {
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType(ContentType.URLENC)
                .formParam("ownerName", "x")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(400);
    }

    // Enforcement on an anonymous form route too. A public booking POST still matches its routing
    // template, so the CSRF filter rejects it before the handler runs, no matter the body.
    @Test
    void tokenlessAnonymousFormPostIsRejected() {
        given().contentType(ContentType.URLENC)
                .formParam("inviteeName", "x")
                .when()
                .post("/admin/some-slug")
                .then()
                .statusCode(400);
    }

    // Happy path: GET the form (sets the csrf-token cookie + renders the matching token), then POST
    // it back as both the cookie and the form field. The filter compares the two and lets it through.
    @Test
    void postWithValidTokenSucceeds() {
        String credential = FormAuth.login();

        Response form = given().cookie("quarkus-credential", credential)
                .when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String token = form.getCookie("csrf-token");
        assertNotNull(token, "GET must set the csrf-token cookie");

        given().cookie("quarkus-credential", credential)
                .cookie("csrf-token", token)
                .contentType(ContentType.URLENC)
                .formParam("csrf-token", token)
                .formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "Europe/Berlin")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(anyOf(is(200), is(302)));
    }
}
