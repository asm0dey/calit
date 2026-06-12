package com.calit.google;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleTokenServiceTest {

    @Inject
    GoogleOAuthConfig config;

    /** Subclass that stubs the single network call so no Google traffic happens in tests. */
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

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("prompt=consent"));
        assertTrue(url.contains("response_type=code"));
        // Scope is URL-encoded (':' -> %3A, '/' -> %2F).
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcalendar"));
        // A signed, non-empty CSRF state is present (stateless — no HttpSession).
        assertTrue(url.contains("&state="));
    }

    @Test
    void stateRoundTripsStatelesslyWithinTtl() {
        GoogleTokenService svc = new GoogleTokenService(config);
        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        String state = svc.issueState(1L, now);

        // A fresh, untampered state validates on any replica and recovers the owner id.
        assertEquals(1L, svc.validateState(state, now.plusSeconds(60)));
        // Expired beyond the TTL window: rejected.
        assertNull(svc.validateState(state, now.plus(GoogleTokenService.STATE_TTL).plusSeconds(1)));
        // Tampered signature: rejected.
        assertNull(svc.validateState(state + "x", now.plusSeconds(60)));
        // Garbage / missing: rejected.
        assertNull(svc.validateState("not-a-state", now));
        assertNull(svc.validateState(null, now));
    }

    @Test
    @TestTransaction
    void exchangeCodePersistsRefreshTokenSingleton() {
        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        StubTokenService svc = new StubTokenService(config,
                new GoogleTokenService.TokenResponse("access-1", "refresh-1",
                        now.plusSeconds(3600), "sub-from-exchange", "owner@example.com"));

        svc.exchangeCode(1L, "auth-code-123", now);

        GoogleCredential c = GoogleCredential.forOwner(1L);
        assertNotNull(c);
        assertEquals("refresh-1", c.refreshToken);
        assertEquals("access-1", c.accessToken);
        assertEquals(now.plusSeconds(3600), c.accessTokenExpiry);
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

        StubTokenService svc = new StubTokenService(config, null); // must NOT be used
        String token = svc.validAccessToken(1L, Instant.parse("2026-06-08T12:00:00Z"));

        assertEquals("cached-access", token);
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

        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        StubTokenService svc = new StubTokenService(config,
                new GoogleTokenService.TokenResponse("fresh-access", null,
                        now.plusSeconds(3600), null, null));

        String token = svc.validAccessToken(1L, now);

        assertEquals("fresh-access", token);
        GoogleCredential reloaded = GoogleCredential.forOwner(1L);
        assertEquals("fresh-access", reloaded.accessToken);
        // Refresh responses omit a new refresh token; the original is preserved.
        assertEquals("refresh-1", reloaded.refreshToken);
        assertEquals(now.plusSeconds(3600), reloaded.accessTokenExpiry);
    }
}
