package site.asm0dey.calit.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Per-instance public site metadata exposed to Qute as {@code {inject:site.*}}.
 *
 * <p>calit is self-hosted and multi-instance: every deployment is its own data controller on its
 * own domain. None of this can be hardcoded, so the operator supplies it via env:
 * {@code GOOGLE_SITE_VERIFICATION} (Google Search Console domain-verification token — differs per
 * domain), plus {@code OPERATOR_NAME} / {@code PRIVACY_CONTACT_EMAIL} which {@code /privacy} and
 * {@code /terms} render so each operator names itself as the controller.</p>
 */
@Named("site")
@ApplicationScoped
public class SiteInfo {

    @ConfigProperty(name = "app.google-site-verification")
    Optional<String> googleSiteVerification;

    @ConfigProperty(name = "app.operator-name")
    Optional<String> operatorName;

    @ConfigProperty(name = "app.privacy-contact")
    Optional<String> privacyContact;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    /** Token for {@code <meta name="google-site-verification">}, or null when unset so {@code {#if}} hides the tag. */
    public String getGoogleVerification() {
        return googleSiteVerification.filter(s -> !s.isBlank()).orElse(null);
    }

    /** Legal entity running this instance; falls back to the public origin so the policy is never blank. */
    public String getOperatorName() {
        return operatorName.filter(s -> !s.isBlank()).orElse(baseUrl);
    }

    /** Contact for data/privacy requests, or null when the operator left it unset. */
    public String getContactEmail() {
        return privacyContact.filter(s -> !s.isBlank()).orElse(null);
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
