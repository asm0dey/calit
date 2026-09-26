package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Shared profile bundling the mutually-compatible runtime feature flags so tests that each need one
 * of them run under a SINGLE Quarkus boot instead of one boot per flag. The flags live on
 * independent axes (signup gate, scheduler grace window, verification meta tag, OIDC admin-group
 * mapping) — none conflicts with another, and none of the absorbed tests asserts the default-off
 * state of a flag it doesn't itself set. Provider/CSRF/OIDC-tenant stay in their own profiles: those
 * are mutually exclusive or build-time and can't be folded in.
 */
public class CommonFeaturesProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
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
