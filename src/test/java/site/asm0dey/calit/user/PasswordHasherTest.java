package site.asm0dey.calit.user;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashHasArgon2idMcfShape() {
        String encoded = hasher.hash("correct horse battery staple");
        assertThat(encoded).as("unexpected encoding: " + encoded).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        var parts = encoded.split("\\$");
        assertThat(parts.length).as("expected 6 MCF segments, got " + encoded).isEqualTo(6);
    }

    @Test
    void verifyAcceptsCorrectPasswordAndRejectsWrong() {
        String encoded = hasher.hash("s3cret-pass");
        assertThat(hasher.verify("s3cret-pass", encoded)).isTrue();
        assertThat(hasher.verify("wrong-pass", encoded)).isFalse();
    }

    @Test
    void saltIsRandomPerHash() {
        assertThat(hasher.hash("same")).isNotEqualTo(hasher.hash("same"));
    }
}
