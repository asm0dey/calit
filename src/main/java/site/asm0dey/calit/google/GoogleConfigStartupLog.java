package site.asm0dey.calit.google;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Logs a secret-free summary of the effective Google config once, at boot. A wrong redirect URI, a
 * missing scope or an unset client secret is the most common self-hosting mistake and is otherwise
 * invisible — the UI only ever says "couldn't reach Google". The client id is public by OAuth design
 * (it ships in every consent URL); the client secret is never logged, only whether it is set.
 *
 * <p>Deliberately its own bean, not a method on {@link GoogleTokenService}: an observer method there
 * would be inherited by the test stub subclasses, turning each into a bean and making
 * GoogleTokenService injection ambiguous.
 */
@ApplicationScoped
public class GoogleConfigStartupLog {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(GoogleConfigStartupLog.class);

    private final GoogleOAuthConfig config;

    @Inject
    public GoogleConfigStartupLog(GoogleOAuthConfig config) {
        this.config = config;
    }

    void logConfig(@Observes StartupEvent ev) {
        var oauth = config.oauth();
        if (oauth.clientId() == null || oauth.clientId().isBlank()) {
            LOG.info("Google OAuth not configured (GOOGLE_OAUTH_CLIENT_ID empty) — running in degraded mode");
            return;
        }
        LOG.infof(
                "Google OAuth configured: clientId=%s clientSecret=%s redirectUri=%s loginRedirectUri=%s scope=%s",
                oauth.clientId(),
                oauth.clientSecret() == null || oauth.clientSecret().isBlank() ? "MISSING" : "set",
                oauth.redirectUri(),
                oauth.loginRedirectUri(),
                oauth.scope());
    }
}
