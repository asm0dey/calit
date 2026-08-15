package site.asm0dey.calit.google;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The config is hand-mocked rather than injected — the subject is which branch runs. @QuarkusTest is
 * nonetheless required: quarkus-jacoco only records what executes inside the Quarkus test run, so a
 * plain JUnit class would pass while reporting zero coverage.
 */
@QuarkusTest
class GoogleConfigStartupLogTest {

    private static GoogleOAuthConfig configWith(String clientId, String clientSecret) {
        var oauth = mock(GoogleOAuthConfig.OAuth.class);
        when(oauth.clientId()).thenReturn(clientId);
        when(oauth.clientSecret()).thenReturn(clientSecret);
        when(oauth.redirectUri()).thenReturn("https://book.example.com/api/google/callback");
        when(oauth.loginRedirectUri()).thenReturn("https://book.example.com/api/google/login/callback");
        when(oauth.scope()).thenReturn("https://www.googleapis.com/auth/calendar openid email");
        var config = mock(GoogleOAuthConfig.class);
        when(config.oauth()).thenReturn(oauth);
        return config;
    }

    @Test
    void degradedModeStopsBeforeReadingTheRedirectUris() {
        var config = configWith("", "");

        new GoogleConfigStartupLog(config).logConfig(new StartupEvent());

        // Taking the degraded branch is observable: the URI accessors are never reached.
        verify(config.oauth(), never()).redirectUri();
        verify(config.oauth(), never()).loginRedirectUri();
    }

    @Test
    void configuredModeLogsTheEffectiveRedirectUrisAndScope() {
        var config = configWith("1234-abc.apps.googleusercontent.com", "secret");

        new GoogleConfigStartupLog(config).logConfig(new StartupEvent());

        verify(config.oauth()).redirectUri();
        verify(config.oauth()).loginRedirectUri();
        verify(config.oauth()).scope();
    }
}
