package site.asm0dey.calit.user;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class UsernamesGoogleTest {
    @Test
    void fromEmailUsesSanitizedLocalPart() {
        assertThat(Usernames.fromEmail("Jane.Doe@example.com")).isEqualTo("jane.doe".replace(".", ""));
        assertThat(Usernames.fromEmail("john-smith@corp.io")).isEqualTo("john-smith");
    }

    @Test
    void fromEmailFallsBackToUserForUnusableInput() {
        assertThat(Usernames.fromEmail("@@@")).isEqualTo("user");
        assertThat(Usernames.fromEmail(null)).isEqualTo("user");
        // local-part "a" is below MIN_LEN -> fallback
        assertThat(Usernames.fromEmail("a@b.com")).isEqualTo("user");
    }

    @Test
    void uniquifyAppendsSuffixOnCollisionAndAvoidsReserved() {
        Set<String> taken = new HashSet<>(Set.of("jane", "jane-2"));
        assertThat(Usernames.uniquify("jane", taken::contains)).isEqualTo("jane-3");
        // Reserved base is replaced before suffixing.
        String fromReserved = Usernames.uniquify("api", s -> false);
        assertThat(Usernames.isValid(fromReserved) && !Usernames.isReserved(fromReserved))
            .as("reserved base must not survive: " + fromReserved)
            .isTrue();
    }

    @Test
    void uniquifyKeepsSuffixedCandidateWithinMaxLength() {
        // exactly MAX_LEN, valid
        var longBase = "a".repeat(64);
        // Taken so a suffix is needed; the produced handle must still be a valid (<=64) handle.
        String result = Usernames.uniquify(longBase, longBase::equals);
        assertThat(Usernames.isValid(result))
            .as("suffixed candidate must remain a valid handle (<=64 chars): " + result + " len=" + result.length())
            .isTrue();
    }
}
