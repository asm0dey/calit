package com.calit.user;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernamesGoogleTest {

    @Test
    void fromEmailUsesSanitizedLocalPart() {
        assertEquals("jane.doe".replace(".", ""), Usernames.fromEmail("Jane.Doe@example.com"));
        assertEquals("john-smith", Usernames.fromEmail("john-smith@corp.io"));
    }

    @Test
    void fromEmailFallsBackToUserForUnusableInput() {
        assertEquals("user", Usernames.fromEmail("@@@"));
        assertEquals("user", Usernames.fromEmail(null));
        assertEquals("user", Usernames.fromEmail("a@b.com")); // local-part "a" is below MIN_LEN -> fallback
    }

    @Test
    void uniquifyAppendsSuffixOnCollisionAndAvoidsReserved() {
        Set<String> taken = new HashSet<>(Set.of("jane", "jane-2"));
        assertEquals("jane-3", Usernames.uniquify("jane", taken::contains));

        // Reserved base is replaced before suffixing.
        String fromReserved = Usernames.uniquify("api", s -> false);
        assertTrue(Usernames.isValid(fromReserved) && !Usernames.isReserved(fromReserved),
                "reserved base must not survive: " + fromReserved);
    }
}
