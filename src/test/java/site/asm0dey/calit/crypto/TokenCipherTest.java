package site.asm0dey.calit.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static final String KEY = "0".repeat(64);

    private final TokenCipher cipher = new TokenCipher(KEY);

    @Test
    void roundTripsAValue() {
        var plaintext = "1//refresh-token-value";
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

    @Test
    void decryptWithWrongKeyThrows() {
        TokenCipher other = new TokenCipher("f".repeat(64));
        String ct = cipher.encrypt("secret");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> other.decrypt(ct));
    }

    @Test
    void decryptTamperedCiphertextThrows() {
        String ct = cipher.encrypt("secret");
        // Flip a character in the base64 body (after the marker) to corrupt the GCM tag/ciphertext.
        var marker = "enc:v1:";
        var body = ct.substring(marker.length());
        var flip = body.charAt(body.length() - 2) == 'A' ? 'B' : 'A';
        var tampered = marker + body.substring(0, body.length() - 2) + flip + body.charAt(body.length() - 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }
}
