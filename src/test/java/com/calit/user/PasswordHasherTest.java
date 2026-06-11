package com.calit.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashHasArgon2idMcfShape() {
        String encoded = hasher.hash("correct horse battery staple");
        assertTrue(encoded.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"),
                "unexpected encoding: " + encoded);
        String[] parts = encoded.split("\\$");
        assertEquals(6, parts.length, "expected 6 MCF segments, got " + encoded);
    }

    @Test
    void verifyAcceptsCorrectPasswordAndRejectsWrong() {
        String encoded = hasher.hash("s3cret-pass");
        assertTrue(hasher.verify("s3cret-pass", encoded));
        assertFalse(hasher.verify("wrong-pass", encoded));
    }

    @Test
    void saltIsRandomPerHash() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"));
    }
}
