package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class GoogleCredentialExpiryTest {
    @Test
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
}
