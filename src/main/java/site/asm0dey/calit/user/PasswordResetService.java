package site.asm0dey.calit.user;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues and consumes single-use password-reset tokens (see {@link PasswordResetToken}). State
 * lives in Postgres so any replica can consume a token minted by another. Mirrors
 * {@link LoginTicketService}, reusing its SHA-256 helper.
 */
@ApplicationScoped
public class PasswordResetService {

    /** Long enough to survive email delivery; short enough to limit a leaked-link window. */
    public static final Duration TTL = Duration.ofMinutes(30);

    // Non-static: keep SecureRandom out of the native image heap (build-time seed is rejected).
    private final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /** Mint a token for {@code userId}, persist its hash, and return the raw token (emailed once). */
    @Transactional
    public String issue(Long userId, Instant now) {
        return issue(userId, now, TTL);
    }

    /** As {@link #issue(Long, Instant)} but with a caller-chosen lifetime (e.g. longer for invites). */
    @Transactional
    public String issue(Long userId, Instant now, Duration ttl) {
        var raw = new byte[32];
        RNG.nextBytes(raw);
        var token = B64URL.encodeToString(raw);

        PasswordResetToken t = new PasswordResetToken();
        t.userId = userId;
        t.tokenHash = LoginTicketService.sha256Hex(token);
        t.expiresAt = now.plus(ttl);
        t.persist();
        return token;
    }

    /**
     * Validate and consume a raw token. Deletes the matching token (single-use) and returns its
     * {@link AppUser}, or null when the token is unknown, expired, or its user no longer exists.
     */
    @Transactional
    public AppUser consume(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PasswordResetToken t = PasswordResetToken.findByTokenHash(LoginTicketService.sha256Hex(token));
        if (t == null) {
            return null;
        }
        Long userId = t.userId;
        Instant expiry = t.expiresAt;
        t.delete(); // single-use: gone whether or not it was still valid
        if (expiry.isBefore(now)) {
            return null;
        }
        return AppUser.findById(userId);
    }
}
