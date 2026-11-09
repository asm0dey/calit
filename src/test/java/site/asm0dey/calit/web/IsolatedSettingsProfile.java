package site.asm0dey.calit.web;

import module java.base;
import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * One boot for tests that each need a single setting most of the suite cannot tolerate. Only add a
 * test here if it asserts just its own setting: real SMTP, Turnstile and a blank Google client would
 * break the default-profile tests. The unreadable policy path falls back to the shipped copy, so the
 * disclosure tests here still see it.
 */
public class IsolatedSettingsProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "calit.turnstile.enabled",
                "true",
                "quarkus.mailer.mock",
                "false",
                "quarkus.mailer.host",
                "mail.example.org",
                "google.oauth.client-id",
                " ",
                "calit.privacy.invitee-erasure",
                "false",
                "app.privacy-contact",
                "privacy@example.com",
                "calit.retention.booking-days",
                "14",
                "app.base-url",
                "https://cal.example.test",
                "app.terms-path",
                "bad\0path.html",
                "app.privacy-policy-path",
                "/nonexistent/path/does-not-exist.html"
        );
    }
}
