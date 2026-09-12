package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.alexmond.notify4j.HttpClientConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The {@code calit.notify.*} knobs, behind accessors so a test can spy this one bean instead of
 * restarting Quarkus under a {@code @TestProfile} per flag combination (same shape as
 * {@code CaptchaProviderConfig}).
 */
@ApplicationScoped
public class NotifyConfig {

    private static final String ALL = "*";

    final Set<String> allowed;

    final boolean allowPrivateTargets;

    final int maxAttempts;

    @Inject
    public NotifyConfig(
            @ConfigProperty(name = "calit.notify.allowed-schemes", defaultValue = ALL) String allowedSchemes,
            @ConfigProperty(name = "calit.notify.allow-private-targets", defaultValue = "true")
                    boolean allowPrivateTargets,
            @ConfigProperty(name = "calit.notify.max-attempts", defaultValue = "3") int maxAttempts) {
        this.allowed = ALL.equals(allowedSchemes.trim())
                ? Set.of()
                : Arrays.stream(allowedSchemes.split(","))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        this.allowPrivateTargets = allowPrivateTargets;
        this.maxAttempts = maxAttempts;
    }

    /** An empty allowlist means "*" — every channel notify4j knows. */
    public boolean schemeAllowed(String scheme) {
        return allowed.isEmpty() || (scheme != null && allowed.contains(scheme.toLowerCase(Locale.ROOT)));
    }

    public boolean allowPrivateTargets() {
        return allowPrivateTargets;
    }

    /**
     * Built from config rather than {@code HttpClientConfig.defaults()} so {@code %test} can pin
     * max-attempts=1. Blocking retry is correct here: delivery runs on a background thread.
     */
    public HttpClientConfig http() {
        return HttpClientConfig.of(Duration.ofSeconds(10), Duration.ofSeconds(10), maxAttempts, Duration.ofSeconds(1));
    }
}
