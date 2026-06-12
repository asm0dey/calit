package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class GoogleTokenServiceIdentityTest {

    @Inject
    GoogleOAuthConfig config;

    static class StubService extends GoogleTokenService {
        StubService(GoogleOAuthConfig config) { super(config); }
        @Override
        protected TokenResponse requestToken(String grantType, String codeOrRefreshToken, Instant now) {
            return new TokenResponse("at", "rt", now.plusSeconds(3600), "sub-123", "me@example.com");
        }
    }

    @Test
    @Transactional
    void reconnectingSameAccountUpdatesRowNotDuplicates() {
        StubService svc = new StubService(config);
        Instant now = Instant.now();
        svc.exchangeCode(1L, "code-1", now);
        svc.exchangeCode(1L, "code-2", now); // same sub -> upsert, not duplicate
        assertEquals(1, GoogleCredential.countForOwner(1L));
        assertEquals("me@example.com", GoogleCredential.findByOwnerAndSub(1L, "sub-123").accountEmail);
    }
}
