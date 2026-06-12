package com.calit.crypto;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM encryption for secrets stored at rest (Google OAuth tokens — SEC-SECRET-02).
 *
 * <p>Stored form: {@code "enc:v1:" + base64(iv || ciphertext||tag)}. The {@code enc:v1:} marker lets
 * {@link #decrypt} tell ciphertext from legacy plaintext, so rows written before this feature shipped
 * are returned unchanged — connected calendars survive the rollout without re-consent.
 *
 * <p>Key: {@code TOKEN_ENCRYPTION_KEY}, a 64-char hex string (32 bytes). Identical on every replica.
 */
@ApplicationScoped
public class TokenCipher {

    private static final String MARKER = "enc:v1:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(@ConfigProperty(name = "token.encryption-key") String hexKey) {
        byte[] raw = hexToBytes(hexKey);
        if (raw.length != 32) {
            throw new IllegalStateException(
                "TOKEN_ENCRYPTION_KEY must decode to 32 bytes (64 hex chars); got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean looksEncrypted(String stored) {
        return stored != null && stored.startsWith(MARKER);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return MARKER + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!looksEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(MARKER.length()));
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] pt = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Token decryption failed", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                "TOKEN_ENCRYPTION_KEY must decode to 32 bytes (64 hex chars).", e);
        }
    }
}
