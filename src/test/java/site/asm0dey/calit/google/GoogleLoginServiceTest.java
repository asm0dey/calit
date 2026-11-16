package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

class GoogleLoginServiceTest {
    final GoogleOAuthConfig config = config();
    final GoogleLoginService loginService = new GoogleLoginService(config);
    final GoogleTokenService tokenService = new GoogleTokenService(config);

    private static GoogleOAuthConfig config() {
        var oauth = mock(GoogleOAuthConfig.OAuth.class);
        when(oauth.clientId()).thenReturn("test-client-id");
        when(oauth.loginRedirectUri()).thenReturn("http://localhost:8080/api/google/login/callback");
        when(oauth.stateSecret()).thenReturn("test-state-secret");
        var config = mock(GoogleOAuthConfig.class);
        when(config.oauth()).thenReturn(oauth);
        return config;
    }

    @Test
    void consentUrlTargetsGoogleWithLoginRedirectAndState() {
        String url = loginService.buildConsentUrl(Instant.parse("2026-06-12T12:00:00Z"));
        assertThat(url).as("points at Google").startsWith("https://accounts.google.com/");
        assertThat(url.contains("login%2Fcallback") || url.contains("login/callback"))
            .as("uses the sign-in redirect URI")
            .isTrue();
        assertThat(url)
            .as("carries a login-purpose state")
            .contains("state=")
            .as("sign-in uses select_account")
            .contains("prompt=select_account")
            .as("requests the openid identity scope")
            .contains("openid");
        assertThat(url.contains("auth%2Fcalendar") || url.contains("auth/calendar"))
            .as("must NOT request the calendar scope on sign-in")
            .isFalse();
    }

    @Test
    void loginStateRoundTrips() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        String state = loginService.issueLoginState(now);
        assertThat(loginService.validateLoginState(state, now)).as("fresh login state is accepted").isTrue();
    }

    @Test
    void forgedExpiredOrCalendarStateRejected() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        String state = loginService.issueLoginState(now);

        assertThat(loginService.validateLoginState(state + "x", now)).as("tampered state rejected").isFalse();
        assertThat(loginService.validateLoginState(null, now)).as("null rejected").isFalse();
        assertThat(loginService.validateLoginState(state, now.plus(GoogleLoginService.STATE_TTL).plusSeconds(60)))
            .as("expired rejected")
            .isFalse();
        // A calendar-purpose state (issued by the calendar flow) must NOT validate as a login state.
        assertThat(loginService.validateLoginState(tokenService.issueState(1L, now), now))
            .as("a calendar-flow state must be rejected by the login validator")
            .isFalse();
        // Blank state rejected.
        assertThat(loginService.validateLoginState("   ", now)).as("blank state rejected").isFalse();
    }
}
