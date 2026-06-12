package com.calit.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Single-use, short-lived ticket that bridges a verified Google identity into a native
 * Quarkus form-auth session. Only the SHA-256 hash of the raw token is stored; the raw token
 * is handed to the browser once and POSTed to /j_security_check, where it is consumed (deleted).
 * High-entropy random tokens make a fast hash sufficient (no password-style brute-force risk).
 */
@Entity
@Table(name = "login_ticket")
public class LoginTicket extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    public String tokenHash;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    public static LoginTicket findByTokenHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResult();
    }
}
