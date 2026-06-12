package com.calit.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCipherTest {

    private static final String KEY = "0".repeat(64);

    private final TokenCipher cipher = new TokenCipher(KEY);

    @Test
    void roundTripsAValue() {
        String plaintext = "1//refresh-token-value";
        String encrypted = cipher.encrypt(plaintext);
        assertNotEquals(plaintext, encrypted);
        assertTrue(cipher.looksEncrypted(encrypted));
        assertEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    void usesAFreshIvPerCall() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
    }

    @Test
    void decryptPassesThroughLegacyPlaintext() {
        assertFalse(cipher.looksEncrypted("1//legacy-plaintext"));
        assertEquals("1//legacy-plaintext", cipher.decrypt("1//legacy-plaintext"));
    }

    @Test
    void handlesNulls() {
        assertNull(cipher.encrypt(null));
        assertNull(cipher.decrypt(null));
        assertFalse(cipher.looksEncrypted(null));
    }
}
