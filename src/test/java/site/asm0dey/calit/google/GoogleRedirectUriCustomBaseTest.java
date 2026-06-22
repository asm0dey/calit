package site.asm0dey.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mirrors a production deployment: with only APP_BASE_URL (app.base-url) set to the public HTTPS host
 * and no explicit GOOGLE_OAUTH_*_REDIRECT_URI override, both redirect URIs must resolve to that host —
 * not localhost. This is the exact scenario that caused the redirect_uri_mismatch on prod.
 */
@QuarkusTest
@TestProfile(GoogleRedirectUriCustomBaseTest.CustomBase.class)
class GoogleRedirectUriCustomBaseTest {

    public static class CustomBase implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.base-url", "https://cal.example.test");
        }
    }

    @Inject
    GoogleOAuthConfig config;

    @Test
    void redirectUrisUseTheConfiguredBaseUrl() {
        assertEquals("https://cal.example.test/api/google/callback", config.oauth().redirectUri());
        assertEquals("https://cal.example.test/api/google/login/callback", config.oauth().loginRedirectUri());
    }
}
