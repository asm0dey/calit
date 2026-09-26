package site.asm0dey.calit.privacy;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Instant;
import java.util.UUID;
import site.asm0dey.calit.user.LoginTicket;
import site.asm0dey.calit.user.PasswordResetToken;

/**
 * Shared test fixture: single-use auth tokens (password-reset, login-ticket) for {@code
 * PurgeSchedulerTest}, seeded in their own transaction (mirrors {@link ErasureFixtures} / {@link
 * ChannelFixtures}). Public — the purger's test lives in {@code site.asm0dey.calit.scheduler}, a
 * different package.
 */
public final class TokenFixtures {
    /**
     * The seeded admin owner — DatabaseResetCallback guarantees id 1, and both tables' user_id
     * cascades from app_user, so a real row is required.
     */
    private static final Long USER = 1L;

    private TokenFixtures() {
    }

    /**
     * Persists a password-reset token expiring at {@code expiresAt}. Returns its id.
     */
    public static Long seedResetToken(Instant expiresAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var t = new PasswordResetToken();
            t.userId = USER;
            t.tokenHash = UUID.randomUUID().toString();
            t.expiresAt = expiresAt;
            t.persist();
            return t.id;
        });
    }

    /**
     * Row count for a password-reset token id — 0 once purged.
     */
    public static long countResetToken(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> PasswordResetToken.count("id", id));
    }

    /**
     * Persists a login ticket expiring at {@code expiresAt}. Returns its id.
     */
    public static Long seedLoginTicket(Instant expiresAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var t = new LoginTicket();
            t.userId = USER;
            t.tokenHash = UUID.randomUUID().toString();
            t.expiresAt = expiresAt;
            t.persist();
            return t.id;
        });
    }

    /**
     * Row count for a login-ticket id — 0 once purged.
     */
    public static long countLoginTicket(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> LoginTicket.count("id", id));
    }
}
