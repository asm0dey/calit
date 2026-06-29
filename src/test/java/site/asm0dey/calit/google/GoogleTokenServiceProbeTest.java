package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleTokenServiceProbeTest {

    @Inject
    GoogleOAuthConfig config;

    /** Stub the single network call: return a token, or throw a chosen exception. */
    static class StubTokenService extends GoogleTokenService {
        GoogleTokenService.TokenResponse next;
        RuntimeException toThrow;

        StubTokenService(GoogleOAuthConfig c) {
            super(c);
        }

        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefresh, Instant now) {
            if (toThrow != null) throw toThrow;
            return next;
        }
    }

    private Long seedFlagged(String sub, boolean needsReconnect, Instant notifiedAt) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt-" + sub;
        c.accessToken = "stale";
        c.accessTokenExpiry = Instant.parse("2030-01-01T00:00:00Z"); // NOT expired on purpose
        c.googleSub = sub;
        c.needsReconnect = needsReconnect;
        c.reconnectNotifiedAt = notifiedAt;
        c.persist();
        c.flush();
        return c.id;
    }

    @Test
    @TestTransaction
    void successClearsFlagAndNotifiedAtEvenWhenTokenNotExpired() {
        var id = seedFlagged("probe-ok", true, Instant.parse("2026-06-15T09:00:00Z"));
        var now = Instant.parse("2026-06-15T10:00:00Z");
        var svc = new StubTokenService(config);
        svc.next = new GoogleTokenService.TokenResponse("fresh", null, now.plusSeconds(3600), null, null);

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertEquals(GoogleTokenService.ProbeResult.OK, r);
        GoogleCredential c = GoogleCredential.findById(id);
        assertEquals("fresh", c.accessToken); // forced refresh ran despite non-expiry
        assertFalse(c.needsReconnect);
        assertNull(c.reconnectNotifiedAt); // recovery resets the notify gate
    }

    @Test
    @TestTransaction
    void invalidGrantFlagsNeedsReconnectAndPreservesNotifiedAt() {
        var id = seedFlagged("probe-dead", false, null);
        var now = Instant.parse("2026-06-15T10:00:00Z");
        var svc = new StubTokenService(config);
        svc.toThrow = new GoogleInvalidGrantException("invalid_grant", null);

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertEquals(GoogleTokenService.ProbeResult.INVALID_GRANT, r);
        GoogleCredential c = GoogleCredential.findById(id);
        assertTrue(c.needsReconnect);
        assertNull(c.reconnectNotifiedAt); // still unset -> notifier will email
    }

    @Test
    @TestTransaction
    void transientErrorChangesNothing() {
        var id = seedFlagged("probe-blip", false, null);
        var now = Instant.parse("2026-06-15T10:00:00Z");
        var svc = new StubTokenService(config);
        svc.toThrow = new IllegalStateException("Google token request I/O error", new IOException("timeout"));

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertEquals(GoogleTokenService.ProbeResult.TRANSIENT, r);
        GoogleCredential c = GoogleCredential.findById(id);
        assertFalse(c.needsReconnect); // a blip must NOT flag (no false alarm)
    }

    @Test
    @TestTransaction
    void missingCredentialReturnsNull() {
        var svc = new StubTokenService(config);
        assertNull(svc.probe(999_999L, Instant.parse("2026-06-15T10:00:00Z")));
    }
}
