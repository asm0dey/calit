package site.asm0dey.calit.user;

import module java.base;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Issues and consumes single-use password-reset tokens (see {@link PasswordResetToken}). State
 * lives in Postgres so any replica can consume a token minted by another. Mirrors
 * {@link LoginTicketService}, reusing its SHA-256 helper.
 */
@ApplicationScoped
public class PasswordResetService {
    /**
     * Long enough to survive email delivery; short enough to limit a leaked-link window.
     */
    public static final Duration TTL = Duration.ofMinutes(30);
    // Non-static: keep SecureRandom out of the native image heap (build-time seed is rejected).
    private final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * Mint a token for {@code userId}, persist its hash, and return the raw token (emailed once).
     */
    @Transactional
    public String issue(Long userId, Instant now) {
        return issue(userId, now, TTL);
    }

    /**
     * As {@link #issue(Long, Instant)} but with a caller-chosen lifetime (e.g. longer for invites).
     */
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
     * The username a still-valid token belongs to, without consuming it (shown on the set-password
     * form so the user and their password manager know which account it is); null otherwise.
     */
    @Transactional
    public String usernameFor(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PasswordResetToken t = PasswordResetToken.findByTokenHash(LoginTicketService.sha256Hex(token));
        if (t == null || t.expiresAt.isBefore(now)) {
            return null;
        }
        // the FK cascades on user delete, so a live token always has its user
        return AppUser.<AppUser>findById(t.userId).username;
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
        // single-use: gone whether or not it was still valid
        t.delete();
        if (expiry.isBefore(now)) {
            return null;
        }
        return AppUser.findById(userId);
    }
}
