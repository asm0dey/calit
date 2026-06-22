package site.asm0dey.calit.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernamesTest {

    @Test
    void normalizeTrimsAndLowercases() {
        assertEquals("alice", Usernames.normalize("  Alice "));
        assertEquals("bob-smith", Usernames.normalize("Bob-Smith"));
    }

    @Test
    void isValidAcceptsGoodHandles() {
        assertTrue(Usernames.isValid("ab"));
        assertTrue(Usernames.isValid("a1"));
        assertTrue(Usernames.isValid("bob-smith"));
        assertTrue(Usernames.isValid("a-b-c-1-2"));
    }

    @Test
    void isValidRejectsBadHandles() {
        assertFalse(Usernames.isValid("a"));           // too short
        assertFalse(Usernames.isValid("-bob"));        // leading hyphen
        assertFalse(Usernames.isValid("bob-"));        // trailing hyphen
        assertFalse(Usernames.isValid("bob--smith"));  // double hyphen
        assertFalse(Usernames.isValid("Bob"));         // uppercase
        assertFalse(Usernames.isValid("bob_smith"));   // underscore
        assertFalse(Usernames.isValid("a".repeat(65)));// too long
        assertFalse(Usernames.isValid(""));
        assertFalse(Usernames.isValid(null));
    }

    @Test
    void isReservedCoversAllReservedWords() {
        for (String w : new String[]{
                "me", "login", "logout", "signup", "setup",
                "booking", "api", "q", "health", "calit", "index",
                "privacy", "terms"}) {
            assertTrue(Usernames.isReserved(w), w + " should be reserved");
        }
        assertFalse(Usernames.isReserved("alice"));
    }

    @Test
    void validateNewReturnsNormalizedWhenFree() {
        assertEquals("alice", Usernames.validateNew("  Alice ", u -> false));
    }

    @Test
    void validateNewThrowsOnInvalidReservedOrTaken() {
        assertThrows(IllegalArgumentException.class, () -> Usernames.validateNew("a", u -> false));
        assertThrows(IllegalArgumentException.class, () -> Usernames.validateNew("Login", u -> false));
        assertThrows(IllegalArgumentException.class, () -> Usernames.validateNew("alice", u -> true));
    }
}
