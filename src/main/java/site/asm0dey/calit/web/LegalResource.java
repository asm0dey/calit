package site.asm0dey.calit.web;

import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.RawString;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Public privacy policy and terms pages. Required for Google OAuth verification: the consent
 * screen must link a same-domain privacy policy that discloses how Google user data is used.
 * Content is operator-customizable via {@code {inject:site.*}} (see {@link SiteInfo}); GET-only,
 * no state mutation, so no CSRF token (mirrors {@code LangResource}).
 *
 * <p>An operator can also replace the entire shipped body with their own HTML fragment via {@code
 * app.privacy-policy-path} / {@code app.terms-path} — see {@link #fragment(Optional)}.
 */
@Path("/")
public class LegalResource {

    @CheckedTemplate
    public static class Templates {
        private Templates() {}

        public static native TemplateInstance privacy(String title, OgCard og, RawString override);

        public static native TemplateInstance terms(String title, OgCard og, RawString override);
    }

    final OgCards ogCards;

    final Optional<String> privacyPolicyPath;

    final Optional<String> termsPath;

    @Inject
    public LegalResource(
            OgCards ogCards,
            @ConfigProperty(name = "app.privacy-policy-path") Optional<String> privacyPolicyPath,
            @ConfigProperty(name = "app.terms-path") Optional<String> termsPath) {
        this.ogCards = ogCards;
        this.privacyPolicyPath = privacyPolicyPath;
        this.termsPath = termsPath;
    }

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy() {
        return Templates.privacy("Privacy Policy", ogCards.product("/privacy"), fragment(privacyPolicyPath));
    }

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms() {
        return Templates.terms("Terms of Service", ogCards.product("/terms"), fragment(termsPath));
    }

    /**
     * The operator's replacement body for a legal page, or null to render the shipped one. Read on
     * every request rather than cached at startup: an operator editing the file should see the
     * change without a restart, and these two pages are not hot. An unreadable path — missing file,
     * or a malformed path string ({@link InvalidPathException}) — logs and falls back to the shipped
     * copy — a missing file must never take /privacy down, because the Google consent screen links
     * it. The warning carries the exception's message, not its stack trace: an operator typo in a
     * config value is not an application error worth a trace dump on every request.
     */
    private RawString fragment(Optional<String> path) {
        return path.filter(p -> !p.isBlank())
                .map(p -> {
                    try {
                        return new RawString(Files.readString(java.nio.file.Path.of(p)));
                    } catch (IOException | InvalidPathException e) {
                        Log.warnf("Could not read legal fragment %s (%s); serving the shipped copy", p, e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }
}
