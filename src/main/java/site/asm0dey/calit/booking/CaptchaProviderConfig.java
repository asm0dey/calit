package site.asm0dey.calit.booking;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
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
    final Optional<String> explicit;
    // Reuse the existing render flag (both turnstile flags come from ${TURNSTILE_ENABLED}).
    final boolean turnstileEnabled;
    final Optional<String> altchaHmacKey;
    final long altchaMaxNumber;
    final Optional<String> turnstileSecret;
    final String turnstileVerifyUrl;
    final Optional<String> turnstileSiteKey;

    @Inject
    public CaptchaProviderConfig(
            @ConfigProperty(name = "calit.captcha.provider") Optional<String> explicit,
            @ConfigProperty(name = "calit.turnstile.enabled", defaultValue = "false") boolean turnstileEnabled,
            @ConfigProperty(name = "calit.captcha.altcha.hmac-key") Optional<String> altchaHmacKey,
            @ConfigProperty(name = "calit.captcha.altcha.max-number", defaultValue = "100000") long altchaMaxNumber,
            @ConfigProperty(name = "calit.abuse.turnstile.secret") Optional<String> turnstileSecret,
            @ConfigProperty(name = "calit.abuse.turnstile.verify-url", defaultValue = "https://challenges.cloudflare."
            + "com/turnstile/v0/siteverify") String turnstileVerifyUrl,
            @ConfigProperty(name = "calit.turnstile.site-key") Optional<String> turnstileSiteKey
    ) {
        this.explicit = explicit;
        this.turnstileEnabled = turnstileEnabled;
        this.altchaHmacKey = altchaHmacKey;
        this.altchaMaxNumber = altchaMaxNumber;
        this.turnstileSecret = turnstileSecret;
        this.turnstileVerifyUrl = turnstileVerifyUrl;
        this.turnstileSiteKey = turnstileSiteKey;
    }

    public String provider() {
        return resolve(explicit.orElse(null), turnstileEnabled);
    }

    // Single source of truth for all CAPTCHA config: AltchaResource, CaptchaVerifier and
    // PublicResource read these accessors (not their own @ConfigProperty fields) so tests can
    // mock this one bean instead of restarting Quarkus under a @TestProfile per flag combination.
    public Optional<String> altchaHmacKey() {
        return altchaHmacKey;
    }

    public long altchaMaxNumber() {
        return altchaMaxNumber;
    }

    public Optional<String> turnstileSecret() {
        return turnstileSecret;
    }

    public String turnstileVerifyUrl() {
        return turnstileVerifyUrl;
    }

    public Optional<String> turnstileSiteKey() {
        return turnstileSiteKey;
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
        if ("altcha".equals(provider()) && altchaHmacKey.filter(s -> !s.isBlank()).isEmpty()) {
            throw new IllegalStateException("CAPTCHA_PROVIDER=altcha requires ALTCHA_HMAC_KEY");
        }
    }
}
