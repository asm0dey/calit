package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.i18n.AppMessageResolver;

@QuarkusTest
class EmailLocaleHebrewTest {

    @Inject
    AppMessageResolver messages;

    @Test
    void hebrewSubjectResolvesAndDiffersFromEnglish() {
        String he = messages.forTag("he").email_confirmed_subject("X");
        String en = messages.forTag("en").email_confirmed_subject("X");
        assertFalse(he.isBlank(), "Hebrew confirmation subject must not be blank");
        assertNotEquals(en, he, "Hebrew subject must differ from English");
        // Placeholder is preserved verbatim
        assertTrue(he.contains("X"), "Subject must keep the {meetingTypeName} value");
    }
}
