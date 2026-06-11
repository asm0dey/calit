package com.calit.user;

import jakarta.enterprise.context.ApplicationScoped;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Argon2id password hashing with OWASP-recommended parameters
 * (m=19456 KiB, t=2, p=1, 16-byte salt, 32-byte output).
 *
 * Encodes to an MCF-style string:
 *   $argon2id$v=19$m=19456,t=2,p=1$<saltBase64NoPad>$<hashBase64NoPad>
 */
@ApplicationScoped
public class PasswordHasher {

    private static final int MEMORY_KIB = 19456;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_LEN = 16;
    private static final int HASH_LEN = 32;
    private static final int VERSION = Argon2Parameters.ARGON2_VERSION_13; // 0x13 == 19

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    public String hash(String raw) {
        byte[] salt = new byte[SALT_LEN];
        RNG.nextBytes(salt);
        byte[] out = derive(raw, salt);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                + "$" + B64.encodeToString(salt)
                + "$" + B64.encodeToString(out);
    }

    public boolean verify(String raw, String encoded) {
        if (raw == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 6 || !"argon2id".equals(parts[1])) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = B64D.decode(parts[4]);
            expected = B64D.decode(parts[5]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = derive(raw, salt, expected.length);
        return constantTimeEquals(actual, expected);
    }

    private byte[] derive(String raw, byte[] salt) {
        return derive(raw, salt, HASH_LEN);
    }

    private byte[] derive(String raw, byte[] salt, int outLen) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(VERSION)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] out = new byte[outLen];
        gen.generateBytes(raw.getBytes(StandardCharsets.UTF_8), out, 0, out.length);
        return out;
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
