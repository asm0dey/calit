package site.asm0dey.calit.web;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SiteInfoTest {
    @Inject
    SiteInfo site;

    @Test
    void unsetOptionalsAreNullAndOperatorFallsBackToBaseUrl() {
        // No GOOGLE_SITE_VERIFICATION / OPERATOR_NAME / PRIVACY_CONTACT_EMAIL in %test.
        assertThat(site.getGoogleVerification()).isNull();
        assertThat(site.getContactEmail()).isNull();
        assertThat(site.getOperatorName()).isEqualTo(site.getBaseUrl());
    }
}
