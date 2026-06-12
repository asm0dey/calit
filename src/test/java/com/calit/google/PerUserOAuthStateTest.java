package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PerUserOAuthStateTest {

    @Inject
    GoogleTokenService tokenService;

    @Inject
    GoogleOAuthConfig oauthConfig;

    @Test
    void stateRoundTripsTheOwnerId() {
        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        String state = tokenService.issueState(42L, now);
        assertEquals(42L, tokenService.validateState(state, now),
                "callback must recover the owner id that initiated /connect");
    }

    @Test
    void forgedOrTamperedStateRejected() {
        Instant now = Instant.parse("2026-06-08T12:00:00Z");
        String state = tokenService.issueState(42L, now);
        String tampered = state.replace(":42:", ":99:"); // flips owner id -> signature mismatch
        assertNull(tokenService.validateState(tampered, now), "tampered state must be rejected");
        assertNull(tokenService.validateState("garbage.value", now), "malformed state must be rejected");
        assertNull(tokenService.validateState(null, now), "null state must be rejected");
    }

    @Test
    void expiredStateRejected() {
        Instant issued = Instant.parse("2026-06-08T12:00:00Z");
        String state = tokenService.issueState(7L, issued);
        Instant tooLate = issued.plus(GoogleTokenService.STATE_TTL).plusSeconds(60);
        assertNull(tokenService.validateState(state, tooLate), "expired state must be rejected");
    }

    @Test
    void consentUrlCarriesSignedStateForOwner() {
        String url = tokenService.buildConsentUrl(7L, Instant.parse("2026-06-08T12:00:00Z"));
        assertTrue(url.contains("state="), "consent URL must include a state param");
        assertTrue(url.startsWith("https://accounts.google.com/"), "consent URL points at Google");
    }

    /** Stubs the network seam so exchangeCode does no real Google call. */
    static final class StubTokenService extends GoogleTokenService {
        StubTokenService(GoogleOAuthConfig config) { super(config); }
        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return new TokenResponse("access-tok", "refresh-tok", now.plusSeconds(3600),
                    "sub-peruser", "u@example.com");
        }
    }

    @Test
    @io.quarkus.test.TestTransaction
    void exchangeCodeWritesCredentialForTheGivenOwner() {
        com.calit.user.AppUser a = com.calit.user.AppUser.create("oauth-a", "x", false);
        a.persistAndFlush();
        com.calit.user.AppUser b = com.calit.user.AppUser.create("oauth-b", "x", false);
        b.persistAndFlush();

        StubTokenService stub = new StubTokenService(oauthConfig);
        stub.exchangeCode(b.id, "any-code", Instant.parse("2026-06-08T12:00:00Z"));

        GoogleCredential credB = GoogleCredential.forOwner(b.id);
        assertNotNull(credB, "credential must be written for owner B");
        assertEquals(b.id, credB.ownerId);
        assertEquals("access-tok", credB.accessToken);
        assertNull(GoogleCredential.forOwner(a.id), "owner A must have no credential");
    }
}
