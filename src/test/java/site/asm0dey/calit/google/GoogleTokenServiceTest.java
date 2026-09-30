package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleTokenServiceTest {
    @Inject
    GoogleOAuthConfig config;

    /**
     * Subclass that stubs the single network call so no Google traffic happens in tests.
     */
    static class StubTokenService extends GoogleTokenService {
        TokenResponse next;

        StubTokenService(GoogleOAuthConfig config, TokenResponse next) {
            super(config);
            this.next = next;
        }

        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return next;
        }
    }

    @Test
    void buildConsentUrlIncludesOfflineAndConsentAndScope() {
        GoogleTokenService svc = new GoogleTokenService(config);
        String url = svc.buildConsentUrl(1L, java.time.Instant.parse("2026-06-08T12:00:00Z"));

        assertThat(url)
            .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
            .contains("access_type=offline")
            .contains("prompt=consent")
            .contains("response_type=code")
            // Scope is URL-encoded (':' -> %3A, '/' -> %2F).
            .contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcalendar")
            // The id_token-enabling scopes are present (raw substrings survive URL-encoding).
            .contains("openid")
            .contains("email")
            // A signed, non-empty CSRF state is present (stateless — no HttpSession).
            .contains("&state=");
    }

    @Test
    void stateRoundTripsStatelesslyWithinTtl() {
        GoogleTokenService svc = new GoogleTokenService(config);
        var now = Instant.parse("2026-06-08T12:00:00Z");
        String state = svc.issueState(1L, now);
        // A fresh, untampered state validates on any replica and recovers the owner id.
        assertThat(svc.validateState(state, now.plusSeconds(60))).isOne();
        // Expired beyond the TTL window: rejected.
        assertThat(svc.validateState(state, now.plus(GoogleTokenService.STATE_TTL).plusSeconds(1))).isNull();
        // Tampered signature: rejected.
        assertThat(svc.validateState(state + "x", now.plusSeconds(60))).isNull();
        // Garbage / missing: rejected.
        assertThat(svc.validateState("not-a-state", now)).isNull();
        assertThat(svc.validateState(null, now)).isNull();
    }

    @Test
    @TestTransaction
    void exchangeCodePersistsRefreshTokenSingleton() {
        var now = Instant.parse("2026-06-08T12:00:00Z");
        var svc = new StubTokenService(
                config,
                new GoogleTokenService.TokenResponse(
                        "access-1",
                        "refresh-1",
                        now.plusSeconds(3600),
                        "sub-from-exchange",
                        "owner@example.com"
                )
        );

        svc.exchangeCode(1L, "auth-code-123", now);

        GoogleCredential c = GoogleCredential.forOwner(1L);
        assertThat(c).isNotNull();
        assertThat(c.refreshToken).isEqualTo("refresh-1");
        assertThat(c.accessToken).isEqualTo("access-1");
        assertThat(c.accessTokenExpiry).isEqualTo(now.plusSeconds(3600));
    }

    @Test
    @TestTransaction
    void validAccessTokenReturnsCachedWhenNotExpired() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "refresh-1";
        c.accessToken = "cached-access";
        c.accessTokenExpiry = Instant.parse("2026-06-08T13:00:00Z");
        c.googleSub = "sub-cached";
        c.persist();
        // must NOT be used
        var svc = new StubTokenService(config, null);
        String token = svc.validAccessToken(c, Instant.parse("2026-06-08T12:00:00Z"));

        assertThat(token).isEqualTo("cached-access");
    }

    @Test
    @TestTransaction
    void validAccessTokenRefreshesWhenExpired() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "refresh-1";
        c.accessToken = "stale-access";
        c.accessTokenExpiry = Instant.parse("2026-06-08T12:00:00Z");
        c.googleSub = "sub-stale";
        c.persist();

        var now = Instant.parse("2026-06-08T12:00:00Z");
        var svc = new StubTokenService(
                config,
                new GoogleTokenService.TokenResponse("fresh-access", null, now.plusSeconds(3600), null, null)
        );

        String token = svc.validAccessToken(c, now);

        assertThat(token).isEqualTo("fresh-access");
        GoogleCredential reloaded = GoogleCredential.forOwner(1L);
        assertThat(reloaded.accessToken).isEqualTo("fresh-access");
        // Refresh responses omit a new refresh token; the original is preserved.
        assertThat(reloaded.refreshToken).isEqualTo("refresh-1");
        assertThat(reloaded.accessTokenExpiry).isEqualTo(now.plusSeconds(3600));
    }
}
