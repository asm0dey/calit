package site.asm0dey.calit.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Single-use, expiring password-reset token. Like {@link LoginTicket}, only the SHA-256 hash of
 * the raw token is stored; the raw token is emailed once and consumed (deleted) on use. High
 * entropy makes a fast hash sufficient (no password-style brute-force risk).
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    public String tokenHash;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    public static PasswordResetToken findByTokenHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResult();
    }
}
