package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleCredentialTest {
    @Test
    @TestTransaction
    void getReturnsNullWhenNotConnected() {
        assertThat(GoogleCredential.forOwner(1L)).isNull();
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
        assertThat(loaded).isNotNull();
        assertThat(loaded.refreshToken).isEqualTo("refresh-abc");
        assertThat(loaded.accessToken).isEqualTo("access-xyz");
        assertThat(loaded.accessTokenExpiry).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    @TestTransaction
    void accessTokenIsExpiredWhenNullOrPast() {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "refresh-abc";
        // No access token yet.
        assertThat(c.isAccessTokenExpired(Instant.parse("2026-06-08T00:00:00Z"))).isTrue();

        c.accessToken = "access-xyz";
        c.accessTokenExpiry = Instant.parse("2026-06-08T00:00:00Z");
        // Exactly at expiry counts as expired (with safety margin).
        assertThat(c.isAccessTokenExpired(Instant.parse("2026-06-08T00:00:00Z"))).isTrue();
        // Comfortably before expiry: not expired.
        assertThat(c.isAccessTokenExpired(Instant.parse("2026-06-07T23:00:00Z"))).isFalse();
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

        assertThat(GoogleCredential.countForOwner(1L)).isEqualTo(2);
        assertThat(GoogleCredential.findByOwnerAndSub(1L, "sub-A").accountEmail).isEqualTo("a@example.com");
        assertThat(GoogleCredential.findByOwnerAndSub(1L, "sub-missing")).isNull();
    }
}
