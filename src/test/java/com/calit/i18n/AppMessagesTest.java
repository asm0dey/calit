package com.calit.i18n;

import io.quarkus.qute.i18n.Localized;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class AppMessagesTest {
    @Inject AppMessages en;
    @Inject @Localized("de") AppMessages de;

    @Test void englishDefault() { assertEquals("Cancel", en.common_cancel()); }
    @Test void germanOverride() { assertEquals("Abbrechen", de.common_cancel()); }

    @Test void signupErrorEnglishNonBlank() {
        String msg = en.auth_signup_error();
        assertFalse(msg == null || msg.isBlank(), "auth_signup_error English must be non-blank");
    }

    @Test void signupErrorGermanNonBlankAndDiffersFromEnglish() {
        String enMsg = en.auth_signup_error();
        String deMsg = de.auth_signup_error();
        assertFalse(deMsg == null || deMsg.isBlank(), "auth_signup_error German must be non-blank");
        assertNotEquals(enMsg, deMsg, "German auth_signup_error must differ from English");
    }
}
