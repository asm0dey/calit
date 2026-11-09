package site.asm0dey.calit.google;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.web.IsolatedSettingsProfile;

/**
 * Mirrors a production deployment: with only APP_BASE_URL (app.base-url) set to the public HTTPS host
 * and no explicit GOOGLE_OAUTH_*_REDIRECT_URI override, both redirect URIs must resolve to that host —
 * not localhost. This is the exact scenario that caused the redirect_uri_mismatch on prod.
 */
@QuarkusTest
@TestProfile(IsolatedSettingsProfile.class)
class GoogleRedirectUriCustomBaseTest {
    @Inject
    GoogleOAuthConfig config;

    @Test
    void redirectUrisUseTheConfiguredBaseUrl() {
        assertThat(config.oauth().redirectUri()).isEqualTo("https://cal.example.test/api/google/callback");
        assertThat(config.oauth().loginRedirectUri()).isEqualTo("https://cal.example.test/api/google/login/callback");
    }
}
