package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SiteInfoTest {
    final SiteInfo site = new SiteInfo(Optional.empty(), Optional.empty(), Optional.empty(), "http://localhost:8080");

    @Test
    void unsetOptionalsAreNullAndOperatorFallsBackToBaseUrl() {
        // No GOOGLE_SITE_VERIFICATION / OPERATOR_NAME / PRIVACY_CONTACT_EMAIL set.
        assertThat(site.getGoogleVerification()).isNull();
        assertThat(site.getContactEmail()).isNull();
        assertThat(site.getOperatorName()).isEqualTo(site.getBaseUrl());
    }
}
