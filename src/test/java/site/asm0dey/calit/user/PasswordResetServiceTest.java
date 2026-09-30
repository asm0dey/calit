package site.asm0dey.calit.user;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
        assertThat(raw).isNotNull();

        AppUser first = reset.consume(raw, now.plusSeconds(5));
        assertThat(first).as("first consume returns the user").isNotNull();
        assertThat(first.id).isEqualTo(uid);

        assertThat(reset.consume(raw, now.plusSeconds(6))).as("token is single-use").isNull();
    }

    @Test
    @TestTransaction
    void expiredTokenRejected() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        var uid = newUserId();
        String raw = reset.issue(uid, now);

        Instant tooLate = now.plus(PasswordResetService.TTL).plus(Duration.ofSeconds(1));
        assertThat(reset.consume(raw, tooLate)).as("expired token is rejected").isNull();
    }

    @Test
    @TestTransaction
    void unknownOrNullTokenRejected() {
        var now = Instant.parse("2026-06-12T12:00:00Z");
        assertThat(reset.consume("not-a-real-token", now)).isNull();
        assertThat(reset.consume(null, now)).isNull();
    }

    @Test
    @TestTransaction
    void customTtlTokenValidBeyond30MinButExpiresAtTtl() {
        // Admin user is always id 1 (test infra).
        var now = Instant.now();
        String token = reset.issue(1L, now, Duration.ofHours(48));
        // Still valid 40 minutes later (would be dead under the 30-min default).
        assertThat(reset.consume(token, now.plusSeconds(40 * 60))).isNotNull();
        // A fresh token is expired just after its 48h window.
        String token2 = reset.issue(1L, now, Duration.ofHours(48));
        assertThat(reset.consume(token2, now.plus(Duration.ofHours(48)).plusSeconds(1))).isNull();
    }
}
