package site.asm0dey.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleLoginServiceTest {

    @Inject
    GoogleLoginService loginService;

    @Inject
    GoogleTokenService tokenService;

    @Test
    void consentUrlTargetsGoogleWithLoginRedirectAndState() {
        String url = loginService.buildConsentUrl(Instant.parse("2026-06-12T12:00:00Z"));
        assertTrue(url.startsWith("https://accounts.google.com/"), "points at Google");
        assertTrue(url.contains("login%2Fcallback") || url.contains("login/callback"),
                "uses the sign-in redirect URI");
        assertTrue(url.contains("state="), "carries a login-purpose state");
        assertTrue(url.contains("prompt=select_account"), "sign-in uses select_account");
        assertTrue(url.contains("openid"), "requests the openid identity scope");
        assertFalse(url.contains("auth%2Fcalendar") || url.contains("auth/calendar"),
                "must NOT request the calendar scope on sign-in");
    }

    @Test
    void loginStateRoundTrips() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        String state = loginService.issueLoginState(now);
        assertTrue(loginService.validateLoginState(state, now), "fresh login state is accepted");
    }

    @Test
    void forgedExpiredOrCalendarStateRejected() {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        String state = loginService.issueLoginState(now);

        assertFalse(loginService.validateLoginState(state + "x", now), "tampered state rejected");
        assertFalse(loginService.validateLoginState(null, now), "null rejected");
        assertFalse(loginService.validateLoginState(state,
                now.plus(GoogleLoginService.STATE_TTL).plusSeconds(60)), "expired rejected");

        // A calendar-purpose state (issued by the calendar flow) must NOT validate as a login state.
        assertFalse(loginService.validateLoginState(tokenService.issueState(1L, now), now),
                "a calendar-flow state must be rejected by the login validator");
        // Blank state rejected.
        assertFalse(loginService.validateLoginState("   ", now), "blank state rejected");
    }
}
