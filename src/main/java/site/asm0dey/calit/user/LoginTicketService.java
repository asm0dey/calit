package site.asm0dey.calit.user;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and consumes single-use login tickets (see {@link LoginTicket}). State lives in
 * Postgres, so any replica that handles the /j_security_check POST can consume a ticket minted
 * by the replica that handled the Google callback.
 */
@ApplicationScoped
public class LoginTicketService {

    /** A ticket is only valid this long after issue — just enough to bridge the auto-submit form. */
    public static final Duration TTL = Duration.ofMinutes(2);

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /** Mint a ticket for {@code userId}, persist its hash, and return the raw token (shown once). */
    @Transactional
    public String issue(Long userId, Instant now) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = B64URL.encodeToString(raw);

        LoginTicket t = new LoginTicket();
        t.userId = userId;
        t.tokenHash = sha256Hex(token);
        t.expiresAt = now.plus(TTL);
        t.persist();
        return token;
    }

    /**
     * Validate and consume a raw token. Deletes the matching ticket (single-use) and returns its
     * {@link AppUser}, or null when the token is unknown, expired, or its user no longer exists.
     */
    @Transactional
    public AppUser consume(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return null;
        }
        LoginTicket t = LoginTicket.findByTokenHash(sha256Hex(token));
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

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
