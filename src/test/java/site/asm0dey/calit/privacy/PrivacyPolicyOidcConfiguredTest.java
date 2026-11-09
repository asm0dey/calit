package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.web.OidcCsrfProfile;

/**
 * When SSO ({@code calit.oidc.enabled=true}) is on, the "Data sharing" list must disclose the
 * identity provider. Top-level class for the same {@code @TestProfile}-requires-restart reason as
 * its siblings in this package. Config overrides live in {@link OidcCsrfProfile}, shared with {@code OidcBridgeFlowTest} — {@link OidcWiremockTestResource} supplies a real (mocked) issuer so
 * {@code quarkus.oidc.tenant-enabled=true} can complete discovery at boot.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(OidcCsrfProfile.class)
class PrivacyPolicyOidcConfiguredTest {
    @Test
    void oidcBulletRendersWhenSsoIsConfigured() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("The single sign-on identity provider configured by the operator"));
    }
}
