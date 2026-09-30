package site.asm0dey.calit.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.qute.i18n.Localized;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AppMessagesTest {
    @Inject
    AppMessages en;
    @Inject
    @Localized("de") AppMessages de;

    @Test
    void englishDefault() {
        assertThat(en.common_cancel()).isEqualTo("Cancel");
    }

    @Test
    void germanOverride() {
        assertThat(de.common_cancel()).isEqualTo("Abbrechen");
    }

    @Test
    void signupErrorEnglishNonBlank() {
        String msg = en.auth_signup_error();
        assertThat(msg == null || msg.isBlank()).as("auth_signup_error English must be non-blank").isFalse();
    }

    @Test
    void signupErrorGermanNonBlankAndDiffersFromEnglish() {
        String enMsg = en.auth_signup_error();
        String deMsg = de.auth_signup_error();
        assertThat(deMsg == null || deMsg.isBlank()).as("auth_signup_error German must be non-blank").isFalse();
        assertThat(deMsg).as("German auth_signup_error must differ from English").isNotEqualTo(enMsg);
    }

    @Test
    void germanPasswordResetSubjectNonBlankAndDiffersFromEnglish() {
        String enMsg = en.email_password_reset_subject();
        String deMsg = de.email_password_reset_subject();
        assertThat(deMsg == null || deMsg.isBlank()).as("German password-reset subject must not be blank").isFalse();
        assertThat(deMsg).as("German password-reset subject must differ from English").isNotEqualTo(enMsg);
    }

    @Test
    void germanGoogleDisconnectedSubjectNonBlankAndDiffersFromEnglish() {
        String enMsg = en.email_google_disconnected_subject();
        String deMsg = de.email_google_disconnected_subject();
        assertThat(deMsg == null || deMsg.isBlank()).as("German google-disconnected subject must not be blank").isFalse();
        assertThat(deMsg).as("German google-disconnected subject must differ from English").isNotEqualTo(enMsg);
    }
}
