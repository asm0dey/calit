package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The Google redirect URIs must derive from {@code app.base-url} so a deployment only needs
 * APP_BASE_URL set — the localhost dev default must never leak into prod (the redirect_uri_mismatch
 * bug). In the test profile app.base-url defaults to http://localhost:8080, so the derived URIs
 * resolve against that. This proves the nested {@code ${...:${app.base-url}/...}} expression expands.
 */
@QuarkusTest
class GoogleRedirectUriDerivationTest {

    @Inject
    GoogleOAuthConfig config;

    @Test
    void redirectUrisDeriveFromAppBaseUrl() {
        assertEquals("http://localhost:8080/api/google/callback", config.oauth().redirectUri());
        assertEquals(
                "http://localhost:8080/api/google/login/callback",
                config.oauth().loginRedirectUri());
    }
}
