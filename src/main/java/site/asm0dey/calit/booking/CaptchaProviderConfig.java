package site.asm0dey.calit.booking;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the effective global CAPTCHA provider. Explicit {@code CAPTCHA_PROVIDER} wins;
 * otherwise falls back to {@code turnstile} when the legacy {@code TURNSTILE_ENABLED} flag is on,
 * else {@code none}. Fails fast at startup if {@code altcha} is selected without an HMAC key.
 */
@ApplicationScoped
public class CaptchaProviderConfig {

    private static final Set<String> VALID = Set.of("none", "turnstile", "altcha");

    @ConfigProperty(name = "calit.captcha.provider")
    Optional<String> explicit;

    // Reuse the existing render flag (both turnstile flags come from ${TURNSTILE_ENABLED}).
    @ConfigProperty(name = "calit.turnstile.enabled", defaultValue = "false")
    boolean turnstileEnabled;

    @ConfigProperty(name = "calit.captcha.altcha.hmac-key")
    Optional<String> altchaHmacKey;

    public String provider() {
        return resolve(explicit.orElse(null), turnstileEnabled);
    }

    static String resolve(String explicit, boolean turnstileEnabled) {
        if (explicit != null && !explicit.isBlank()) {
            var p = explicit.trim().toLowerCase(Locale.ROOT);
            if (!VALID.contains(p)) {
                throw new IllegalArgumentException("Invalid CAPTCHA_PROVIDER: " + explicit);
            }
            return p;
        }
        return turnstileEnabled ? "turnstile" : "none";
    }

    // Fail fast: altcha with no HMAC key would silently accept forged solutions.
    void validate(@Observes StartupEvent ev) {
        if ("altcha".equals(provider())
                && altchaHmacKey.filter(s -> !s.isBlank()).isEmpty()) {
            throw new IllegalStateException("CAPTCHA_PROVIDER=altcha requires ALTCHA_HMAC_KEY");
        }
    }
}
