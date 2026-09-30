package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleTokenServiceIdentityTest {
    @Inject
    GoogleOAuthConfig config;

    static class StubService extends GoogleTokenService {
        StubService(GoogleOAuthConfig config) {
            super(config);
        }

        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return new TokenResponse("at", "rt", now.plusSeconds(3600), "sub-123", "me@example.com");
        }
    }

    static class FailingRefreshService extends GoogleTokenService {
        FailingRefreshService(GoogleOAuthConfig config) {
            super(config);
        }

        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            throw new IllegalStateException("refresh failed");
        }
    }

    @Test
    void failedRefreshFlagsNeedsReconnectInSeparateTransaction() {
        var now = Instant.now();
        Long credId = io.quarkus.narayana.jta.QuarkusTransaction
            .requiringNew()
            .call(() -> {
                GoogleCredential c = new GoogleCredential();
                c.ownerId = 1L;
                c.refreshToken = "rt";
                c.googleSub = "sub-fail";
                c.accessToken = "old";
                // already expired
                c.accessTokenExpiry = now.minusSeconds(60);
                c.persist();
                return c.id;
            });
        var svc = new FailingRefreshService(config);
        GoogleCredential c =
                io.quarkus.narayana.jta.QuarkusTransaction
            .requiringNew()
            .call(() -> GoogleCredential.findById(credId));
        assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> svc.validAccessToken(c, now));
        GoogleCredential reloaded =
                io.quarkus.narayana.jta.QuarkusTransaction
            .requiringNew()
            .call(() -> GoogleCredential.findById(credId));
        assertThat(reloaded.needsReconnect).as("needsReconnect must be committed despite the rethrow").isTrue();
    }

    @Test
    @Transactional
    void reconnectingSameAccountUpdatesRowNotDuplicates() {
        var svc = new StubService(config);
        var now = Instant.now();
        svc.exchangeCode(1L, "code-1", now);
        // same sub -> upsert, not duplicate
        svc.exchangeCode(1L, "code-2", now);
        assertThat(GoogleCredential.countForOwner(1L)).isOne();
        assertThat(GoogleCredential.findByOwnerAndSub(1L, "sub-123").accountEmail).isEqualTo("me@example.com");
    }
}
