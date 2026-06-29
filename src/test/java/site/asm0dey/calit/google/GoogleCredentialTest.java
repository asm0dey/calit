package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleCredentialTest {

    @Test
    @TestTransaction
    void getReturnsNullWhenNotConnected() {
        assertNull(GoogleCredential.forOwner(1L));
    }

    @Test
    @TestTransaction
    void persistsAndReadsSingletonWithTokens() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "refresh-abc";
        c.accessToken = "access-xyz";
        c.accessTokenExpiry = Instant.parse("2030-01-01T00:00:00Z");
        c.googleSub = "sub-singleton";
        c.persist();

        GoogleCredential loaded = GoogleCredential.forOwner(1L);
        assertNotNull(loaded);
        assertEquals("refresh-abc", loaded.refreshToken);
        assertEquals("access-xyz", loaded.accessToken);
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), loaded.accessTokenExpiry);
    }

    @Test
    @TestTransaction
    void accessTokenIsExpiredWhenNullOrPast() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "refresh-abc";
        // No access token yet.
        assertTrue(c.isAccessTokenExpired(Instant.parse("2026-06-08T00:00:00Z")));

        c.accessToken = "access-xyz";
        c.accessTokenExpiry = Instant.parse("2026-06-08T00:00:00Z");
        // Exactly at expiry counts as expired (with safety margin).
        assertTrue(c.isAccessTokenExpired(Instant.parse("2026-06-08T00:00:00Z")));
        // Comfortably before expiry: not expired.
        assertEquals(false, c.isAccessTokenExpired(Instant.parse("2026-06-07T23:00:00Z")));
    }

    @Test
    @Transactional
    void multipleAccountsPerOwnerAreFoundByOwnerAndSub() {
        GoogleCredential a = new GoogleCredential();
        a.ownerId = 1L;
        a.refreshToken = "rt-A";
        a.googleSub = "sub-A";
        a.accountEmail = "a@example.com";
        a.persist();
        GoogleCredential b = new GoogleCredential();
        b.ownerId = 1L;
        b.refreshToken = "rt-B";
        b.googleSub = "sub-B";
        b.accountEmail = "b@example.com";
        b.persist();

        assertEquals(2, GoogleCredential.countForOwner(1L));
        assertEquals("a@example.com", GoogleCredential.findByOwnerAndSub(1L, "sub-A").accountEmail);
        assertNull(GoogleCredential.findByOwnerAndSub(1L, "sub-missing"));
    }
}
