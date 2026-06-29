package site.asm0dey.calit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertNull(site.getGoogleVerification());
        assertNull(site.getContactEmail());
        assertEquals(site.getBaseUrl(), site.getOperatorName());
    }
}
