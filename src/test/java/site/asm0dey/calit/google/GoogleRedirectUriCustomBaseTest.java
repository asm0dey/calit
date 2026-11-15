package site.asm0dey.calit.google;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

/**
 * Mirrors a production deployment: with only APP_BASE_URL (app.base-url) set to the public HTTPS host
 * and no explicit GOOGLE_OAUTH_*_REDIRECT_URI override, both redirect URIs must resolve to that host —
 * not localhost. This is the exact scenario that caused the redirect_uri_mismatch on prod.
 */
@QuarkusComponentTest
@TestConfigProperty(key = "app.base-url", value = "https://cal.example.test")
class GoogleRedirectUriCustomBaseTest {
    // A test field cannot take the synthetic config-mapping bean, so a nested component injects it.
    @Singleton
    static class Probe {
        final GoogleOAuthConfig config;

        @Inject
        Probe(GoogleOAuthConfig config) {
            this.config = config;
        }
    }

    @Inject
    Probe probe;

    @Test
    void redirectUrisUseTheConfiguredBaseUrl() {
        var config = probe.config;
        assertThat(config.oauth().redirectUri()).isEqualTo("https://cal.example.test/api/google/callback");
        assertThat(config.oauth().loginRedirectUri()).isEqualTo("https://cal.example.test/api/google/login/callback");
    }
}
