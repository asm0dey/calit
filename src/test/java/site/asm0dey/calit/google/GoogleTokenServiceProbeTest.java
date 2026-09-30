package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleTokenServiceProbeTest {
    @Inject
    GoogleOAuthConfig config;

    /**
     * Stub the single network call: return a token, or throw a chosen exception.
     */
    static class StubTokenService extends GoogleTokenService {
        GoogleTokenService.TokenResponse next;
        RuntimeException toThrow;

        StubTokenService(GoogleOAuthConfig c) {
            super(c);
        }

        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefresh, Instant now) {
            if (toThrow != null) {
                throw toThrow;
            }
            return next;
        }
    }

    private Long seedFlagged(String sub, boolean needsReconnect, Instant notifiedAt) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt-" + sub;
        c.accessToken = "stale";
        // NOT expired on purpose
        c.accessTokenExpiry = Instant.parse("2030-01-01T00:00:00Z");
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

        assertThat(r).isEqualTo(GoogleTokenService.ProbeResult.OK);
        GoogleCredential c = GoogleCredential.findById(id);
        // forced refresh ran despite non-expiry
        assertThat(c.accessToken).isEqualTo("fresh");
        assertThat(c.needsReconnect).isFalse();
        // recovery resets the notify gate
        assertThat(c.reconnectNotifiedAt).isNull();
    }

    @Test
    @TestTransaction
    void invalidGrantFlagsNeedsReconnectAndPreservesNotifiedAt() {
        var id = seedFlagged("probe-dead", false, null);
        var now = Instant.parse("2026-06-15T10:00:00Z");
        var svc = new StubTokenService(config);
        svc.toThrow = new GoogleInvalidGrantException("invalid_grant", null);

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertThat(r).isEqualTo(GoogleTokenService.ProbeResult.INVALID_GRANT);
        GoogleCredential c = GoogleCredential.findById(id);
        assertThat(c.needsReconnect).isTrue();
        // still unset -> notifier will email
        assertThat(c.reconnectNotifiedAt).isNull();
    }

    @Test
    @TestTransaction
    void transientErrorChangesNothing() {
        var id = seedFlagged("probe-blip", false, null);
        var now = Instant.parse("2026-06-15T10:00:00Z");
        var svc = new StubTokenService(config);
        svc.toThrow = new IllegalStateException("Google token request I/O error", new IOException("timeout"));

        GoogleTokenService.ProbeResult r = svc.probe(id, now);

        assertThat(r).isEqualTo(GoogleTokenService.ProbeResult.TRANSIENT);
        GoogleCredential c = GoogleCredential.findById(id);
        // a blip must NOT flag (no false alarm)
        assertThat(c.needsReconnect).isFalse();
    }

    @Test
    @TestTransaction
    void missingCredentialReturnsNull() {
        var svc = new StubTokenService(config);
        assertThat(svc.probe(999_999L, Instant.parse("2026-06-15T10:00:00Z"))).isNull();
    }
}
