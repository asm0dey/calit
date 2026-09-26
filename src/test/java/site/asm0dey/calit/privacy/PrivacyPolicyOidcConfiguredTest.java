package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When SSO ({@code calit.oidc.enabled=true}) is on, the "Data sharing" list must disclose the
 * identity provider. Top-level class for the same {@code @TestProfile}-requires-restart reason as
 * its siblings in this package. Config overrides mirror {@code OidcBridgeFlowTest}'s
 * {@code OidcOn} profile — {@link OidcWiremockTestResource} supplies a real (mocked) issuer so
 * {@code quarkus.oidc.tenant-enabled=true} can complete discovery at boot.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(PrivacyPolicyOidcConfiguredTest.OidcOn.class)
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

    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.oidc.enabled",
                    "true",
                    "quarkus.oidc.tenant-enabled",
                    "true",
                    "quarkus.oidc.auth-server-url",
                    "${keycloak.url}/realms/quarkus",
                    "quarkus.oidc.client-id",
                    "quarkus-web-app",
                    "quarkus.oidc.credentials.secret",
                    "secret",
                    "quarkus.oidc.application-type",
                    "web-app"
            );
        }
    }
}
