package site.asm0dey.calit.web;

import module java.base;
import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Shared profile bundling the mutually-compatible runtime feature flags so tests that each need one
 * of them run under a SINGLE Quarkus boot instead of one boot per flag. The flags live on
 * independent axes (signup gate, scheduler grace window, verification meta tag, OIDC admin-group
 * mapping) — none conflicts with another, and none of the absorbed tests asserts the default-off
 * state of a flag it doesn't itself set. Settings that would break these tests live in {@link IsolatedSettingsProfile} and
 * {@link OidcCsrfProfile}. The policy override is here because no test in this profile reads {@code /privacy}.
 */
public class CommonFeaturesProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Path policy;
        try {
            policy = Files.createTempFile("policy", ".html");
            Files.writeString(policy, "<h1>OPERATOR SUPPLIED POLICY</h1>");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Map.of(
                "app.privacy-policy-path",
                policy.toAbsolutePath().toString(),
                "calit.signup.enabled",
                "true",
                "calit.scheduler.grace-seconds",
                "120",
                "app.google-site-verification",
                "tok_calit_test_123",
                "calit.oidc.admin-group",
                "calit-admins"
        );
    }
}
