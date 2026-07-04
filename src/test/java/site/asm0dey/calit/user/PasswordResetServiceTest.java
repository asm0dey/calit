package site.asm0dey.calit.user;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PasswordResetServiceTest {

    @Inject
    PasswordResetService reset;

    private Long newUserId() {
        AppUser u = AppUser.createGoogleUser("reset-user", "sub-" + System.nanoTime());
        u.persistAndFlush();
        return u.id;
    }

    @Test
    @TestTransaction
    void tokenConsumedExactlyOnce() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        var uid = newUserId();

        String raw = reset.issue(uid, now);
        assertNotNull(raw);

        AppUser first = reset.consume(raw, now.plusSeconds(5));
        assertNotNull(first, "first consume returns the user");
        assertEquals(uid, first.id);

        assertNull(reset.consume(raw, now.plusSeconds(6)), "token is single-use");
    }

    @Test
    @TestTransaction
    void expiredTokenRejected() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        var uid = newUserId();
        String raw = reset.issue(uid, now);

        Instant tooLate = now.plus(PasswordResetService.TTL).plus(Duration.ofSeconds(1));
        assertNull(reset.consume(raw, tooLate), "expired token is rejected");
    }

    @Test
    @TestTransaction
    void unknownOrNullTokenRejected() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        assertNull(reset.consume("not-a-real-token", now));
        assertNull(reset.consume(null, now));
    }

    @Test
    @TestTransaction
    void customTtlTokenValidBeyond30MinButExpiresAtTtl() {
        // Admin user is always id 1 (test infra).
        var now = Instant.now();
        String token = reset.issue(1L, now, Duration.ofHours(48));

        // Still valid 40 minutes later (would be dead under the 30-min default).
        assertNotNull(reset.consume(token, now.plusSeconds(40 * 60)));

        // A fresh token is expired just after its 48h window.
        String token2 = reset.issue(1L, now, Duration.ofHours(48));
        assertNull(reset.consume(token2, now.plus(Duration.ofHours(48)).plusSeconds(1)));
    }
}
