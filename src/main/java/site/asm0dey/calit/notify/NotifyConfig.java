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

    /**
     * Built once per bean rather than per delivery: {@link HttpClientConfig#of} constructs an
     * {@code HttpClient}, and a reminder batch fanning out hundreds of events would otherwise create
     * hundreds of clients and their selector threads with no connection reuse. A {@code final} field in
     * an {@code @ApplicationScoped} bean is safely published by the JMM with no synchronisation, and
     * being an instance field (never a static) keeps the native-image contract intact -- see
     * Dockerfile.native's --initialize-at-run-time=org.alexmond.notify4j.HttpClientConfig.
     */
    final HttpClientConfig http;

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
        this.http =
                HttpClientConfig.of(Duration.ofSeconds(10), Duration.ofSeconds(10), maxAttempts, Duration.ofSeconds(1));
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
     *
     * <p>Built ONCE and reused: {@code HttpClientConfig.of} builds a real {@code HttpClient}, so a
     * per-delivery call would give a reminder tick fanning out 200 events 200 clients (and their
     * selector threads) with no connection reuse. An INSTANCE field, not a static: a static holding a
     * live {@code HttpClient} is exactly the build-time-heap hazard the native image's
     * {@code --initialize-at-run-time} flag exists to work around.
     */
    public HttpClientConfig http() {
        return http;
    }
}
