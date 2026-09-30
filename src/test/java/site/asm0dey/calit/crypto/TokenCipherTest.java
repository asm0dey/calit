package site.asm0dey.calit.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class TokenCipherTest {
    private static final String KEY = "0".repeat(64);
    private final TokenCipher cipher = new TokenCipher(KEY);

    @Test
    void roundTripsAValue() {
        var plaintext = "1//refresh-token-value";
        String encrypted = cipher.encrypt(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(cipher.looksEncrypted(encrypted)).isTrue();
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void usesAFreshIvPerCall() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void decryptPassesThroughLegacyPlaintext() {
        assertThat(cipher.looksEncrypted("1//legacy-plaintext")).isFalse();
        assertThat(cipher.decrypt("1//legacy-plaintext")).isEqualTo("1//legacy-plaintext");
    }

    @Test
    void handlesNulls() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.looksEncrypted(null)).isFalse();
    }

    @Test
    void decryptWithWrongKeyThrows() {
        TokenCipher other = new TokenCipher("f".repeat(64));
        String ct = cipher.encrypt("secret");
        assertThatThrownBy(() -> other.decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptTamperedCiphertextThrows() {
        String ct = cipher.encrypt("secret");
        // Flip a character in the base64 body (after the marker) to corrupt the GCM tag/ciphertext.
        var marker = "enc:v1:";
        var body = ct.substring(marker.length());
        var flip = body.charAt(body.length() - 2) == 'A' ? 'B' : 'A';
        var tampered = marker + body.substring(0, body.length() - 2) + flip + body.charAt(body.length() - 1);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }
}
