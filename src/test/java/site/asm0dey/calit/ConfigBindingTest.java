package site.asm0dey.calit;

import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.CaptchaProviderConfig;
import site.asm0dey.calit.privacy.PrivacyConfig;
import site.asm0dey.calit.privacy.PrivacyFacts;
import site.asm0dey.calit.user.SignupConfig;
import site.asm0dey.calit.web.LegalFragments;
import site.asm0dey.calit.web.SiteInfo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP tests swap these beans for instances built with their test value, so nothing there proves
 * the {@code @ConfigProperty} names still bind. This does, without booting the app: a renamed key would
 * leave the bean on its default and fail here.
 */
@QuarkusComponentTest
@TestConfigProperty(key = "calit.signup.enabled", value = "true")
@TestConfigProperty(key = "app.google-site-verification", value = "tok_binding")
@TestConfigProperty(key = "app.privacy-contact", value = "privacy@example.com")
@TestConfigProperty(key = "app.base-url", value = "https://cal.example.test")
@TestConfigProperty(key = "app.privacy-policy-path", value = "pom.xml")
@TestConfigProperty(key = "app.terms-path", value = "pom.xml")
@TestConfigProperty(key = "calit.privacy.invitee-erasure", value = "false")
@TestConfigProperty(key = "calit.retention.booking-days", value = "14")
@TestConfigProperty(key = "calit.turnstile.enabled", value = "true")
@TestConfigProperty(key = "quarkus.mailer.mock", value = "false")
@TestConfigProperty(key = "quarkus.mailer.host", value = "mail.example.org")
@TestConfigProperty(key = "google.oauth.client-id", value = " ")
class ConfigBindingTest {
    @Inject
    SignupConfig signup;
    @Inject
    SiteInfo site;
    @Inject
    LegalFragments legal;
    @Inject
    PrivacyConfig privacy;
    @Inject
    PrivacyFacts facts;
    @Inject
    CaptchaProviderConfig captcha;

    @Test
    void signupBinds() {
        assertThat(signup.enabled()).isTrue();
    }

    @Test
    void siteInfoBinds() {
        assertThat(site.getGoogleVerification()).isEqualTo("tok_binding");
        assertThat(site.getContactEmail()).isEqualTo("privacy@example.com");
        assertThat(site.getBaseUrl()).isEqualTo("https://cal.example.test");
    }

    /**
     * Surefire runs from the module root, where {@code pom.xml} is a readable file.
     */
    @Test
    void legalFragmentPathsBind() {
        assertThat(legal.privacy()).isNotNull();
        assertThat(legal.terms()).isNotNull();
    }

    @Test
    void privacyConfigBinds() {
        assertThat(privacy.inviteeErasureEnabled()).isFalse();
        assertThat(privacy.bookingRetentionDays()).contains(14);
    }

    @Test
    void privacyFactsBind() {
        assertThat(facts.getSmtpHost()).isEqualTo("mail.example.org");
        assertThat(facts.isGoogleConfigured()).isFalse();
        assertThat(captcha.provider()).isEqualTo("turnstile");
    }
}
