package site.asm0dey.calit.web;

import module java.base;
import io.quarkus.logging.Log;
import io.quarkus.qute.RawString;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The operator's replacement bodies for the legal pages ({@code app.privacy-policy-path} / {@code
 * app.terms-path}). A bean of its own so a test can swap the paths without a test profile.
 */
@ApplicationScoped
public class LegalFragments {
    final Optional<String> privacyPolicyPath;
    final Optional<String> termsPath;

    @Inject
    public LegalFragments(
            @ConfigProperty(name = "app.privacy-policy-path") Optional<String> privacyPolicyPath,
            @ConfigProperty(name = "app.terms-path") Optional<String> termsPath
    ) {
        this.privacyPolicyPath = privacyPolicyPath;
        this.termsPath = termsPath;
    }

    public RawString privacy() {
        return fragment(privacyPolicyPath);
    }

    public RawString terms() {
        return fragment(termsPath);
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
    private static RawString fragment(Optional<String> path) {
        return path.filter(p -> !p.isBlank()).map(p -> {
            try {
                return new RawString(Files.readString(Path.of(p)));
            } catch (IOException | InvalidPathException e) {
                Log.warnf("Could not read legal fragment %s (%s); serving the shipped copy", p, e.getMessage());
                return null;
            }
        }).orElse(null);
    }
}
