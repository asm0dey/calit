package site.asm0dey.calit.email;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(he.isBlank()).as("Hebrew confirmation subject must not be blank").isFalse();
        assertThat(he).as("Hebrew subject must differ from English").isNotEqualTo(en);
        // Placeholder is preserved verbatim
        assertThat(he).as("Subject must keep the {meetingTypeName} value").contains("X");
    }
}
