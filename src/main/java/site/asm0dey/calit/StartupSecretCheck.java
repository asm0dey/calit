package site.asm0dey.calit;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fail-closed guard for production secrets. The {@code %prod} config already drops the insecure dev
 * defaults so an UNSET env var fails boot — but a deployer could still set the env var to the literal
 * dev value or a too-short string. This bean (prod profile only) rejects those at startup so the app
 * never serves traffic with a forgeable OAuth-state HMAC key or a weak session-cookie key.
 */
@ApplicationScoped
@IfBuildProfile("prod")
public class StartupSecretCheck {

    /** Any secret containing this marker is one of the committed dev placeholders. */
    private static final String DEV_MARKER = "dev-only-insecure";

    /**
     * The committed dev default for {@code TOKEN_ENCRYPTION_KEY}. A 64-hex AES key can't embed the
     * text {@link #DEV_MARKER} (hex is 0-9a-f only), so this all-zeros placeholder is rejected by
     * exact value match instead of by marker match.
     */
    private static final String TOKEN_KEY_DEV_DEFAULT =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @ConfigProperty(name = "google.oauth.state-secret")
    String stateSecret;

    @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
    String sessionKey;

    @ConfigProperty(name = "token.encryption-key")
    String tokenKey;

    void onStart(@Observes StartupEvent ev) {
        // HMAC key for the OAuth CSRF state; >=32 chars (e.g. `openssl rand -hex 32`).
        validate("GOOGLE_OAUTH_STATE_SECRET", stateSecret, 32);
        // Quarkus form-auth cookie encryption key; framework minimum is 16 chars.
        validate("SESSION_ENCRYPTION_KEY", sessionKey, 16);
        // AES-256-GCM token key (SEC-SECRET-02) — a 64-hex-character value. The blank and length
        // checks run here; the all-zeros dev placeholder cannot carry the dev marker, so it is
        // rejected by exact value just below.
        validate("TOKEN_ENCRYPTION_KEY", tokenKey, 64);
        if (TOKEN_KEY_DEV_DEFAULT.equals(tokenKey)) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY is still the insecure all-zeros development default — "
                            + "set a real key (openssl rand -hex 32).");
        }
    }

    private static void validate(String envName, String value, int minLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(envName + " must be set in production.");
        }
        if (value.contains(DEV_MARKER)) {
            throw new IllegalStateException(
                    envName + " is still the insecure development default — set a real secret.");
        }
        if (value.length() < minLength) {
            throw new IllegalStateException(
                    envName + " is too short (" + value.length() + " chars); require >= " + minLength + ".");
        }
    }
}
