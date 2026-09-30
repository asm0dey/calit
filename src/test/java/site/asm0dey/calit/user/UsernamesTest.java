package site.asm0dey.calit.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class UsernamesTest {
    @Test
    void normalizeTrimsAndLowercases() {
        assertThat(Usernames.normalize("  Alice ")).isEqualTo("alice");
        assertThat(Usernames.normalize("Bob-Smith")).isEqualTo("bob-smith");
    }

    @Test
    void isValidAcceptsGoodHandles() {
        assertThat(Usernames.isValid("ab")).isTrue();
        assertThat(Usernames.isValid("a1")).isTrue();
        assertThat(Usernames.isValid("bob-smith")).isTrue();
        assertThat(Usernames.isValid("a-b-c-1-2")).isTrue();
    }

    @Test
    void isValidRejectsBadHandles() {
        // too short
        assertThat(Usernames.isValid("a")).isFalse();
        // leading hyphen
        assertThat(Usernames.isValid("-bob")).isFalse();
        // trailing hyphen
        assertThat(Usernames.isValid("bob-")).isFalse();
        // double hyphen
        assertThat(Usernames.isValid("bob--smith")).isFalse();
        // uppercase
        assertThat(Usernames.isValid("Bob")).isFalse();
        // underscore
        assertThat(Usernames.isValid("bob_smith")).isFalse();
        // too long
        assertThat(Usernames.isValid("a".repeat(65))).isFalse();
        assertThat(Usernames.isValid("")).isFalse();
        assertThat(Usernames.isValid(null)).isFalse();
    }

    @Test
    void isReservedCoversAllReservedWords() {
        for (String w :
                new String[] {
                "me",
                "login",
                "logout",
                "signup",
                "setup",
                "booking",
                "api",
                "q",
                "health",
                "calit",
                "index",
                "privacy",
                "terms"
        }) {
            assertThat(Usernames.isReserved(w)).as(w + " should be reserved").isTrue();
        }
        assertThat(Usernames.isReserved("alice")).isFalse();
    }

    @Test
    void validateNewReturnsNormalizedWhenFree() {
        assertThat(Usernames.validateNew("  Alice ", u -> false)).isEqualTo("alice");
    }

    @Test
    void validateNewThrowsOnInvalidReservedOrTaken() {
        assertThatThrownBy(() -> Usernames.validateNew("a", u -> false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Usernames.validateNew("Login", u -> false)).isInstanceOf(
                IllegalArgumentException.class
        );
        assertThatThrownBy(() -> Usernames.validateNew("alice", u -> true)).isInstanceOf(IllegalArgumentException.class);
    }
}
