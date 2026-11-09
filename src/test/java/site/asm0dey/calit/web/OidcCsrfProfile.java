package site.asm0dey.calit.web;

import module java.base;
import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * One boot for SSO and real CSRF enforcement. Kept apart from the other profiles because CSRF rejects
 * the tokenless form POSTs used across the suite. {@code ${keycloak.url}} comes from {@code
 * OidcWiremockTestResource}, which the OIDC tests declare.
 */
public class OidcCsrfProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-csrf.enabled",
                "true",
                "calit.oidc.enabled",
                "true",
                "quarkus.oidc.tenant-enabled",
                "true",
                "calit.signup.enabled",
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
