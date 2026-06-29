package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Feature 16: server-side Cloudflare Turnstile verification. When the flag is off, {@link #verify}
 * is a no-op success (so local dev/tests never call Cloudflare). When on, it POSTs the token to the
 * siteverify endpoint and throws {@link AbuseException} (HTTP 400) on a non-success response.
 */
@ApplicationScoped
public class TurnstileVerifier {

    @ConfigProperty(name = "calit.abuse.turnstile.enabled", defaultValue = "false")
    boolean enabled;

    // Quarkus 3.35 SmallRye treats an empty-string config value as null for the String converter,
    // which would fail a plain `String` @ConfigProperty at startup. Optional<String> tolerates the
    // empty/unset secret (the off-by-default local/test case) and is only present when configured.
    @ConfigProperty(name = "calit.abuse.turnstile.secret")
    Optional<String> secret;

    @ConfigProperty(
            name = "calit.abuse.turnstile.verify-url",
            defaultValue = "https://challenges.cloudflare.com/turnstile/v0/siteverify")
    String verifyUrl;

    // SEC-SSRF-01: bound the synchronous booking-path call so a hung Cloudflare upstream can't pin
    // a request thread indefinitely. Fixed destination (no SSRF) — this is availability hardening.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Matches the success flag in the siteverify JSON, tolerating whitespace: {@code "success" : true}. */
    private static final Pattern SUCCESS = Pattern.compile("\"success\"\\s*:\\s*true");

    /** Throws AbuseException (400) if Turnstile is enabled and the token does not verify. */
    public void verify(String token) {
        if (!enabled) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new AbuseException("Missing Turnstile token");
        }
        try {
            var body = "secret=" + URLEncoder.encode(secret.orElse(""), StandardCharsets.UTF_8) + "&response="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8);
            var req = HttpRequest.newBuilder(URI.create(verifyUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // Cloudflare returns JSON {"success": true|false, ...} (note the space after the colon).
            // Match with a whitespace-tolerant regex so the real, spaced response is accepted; this
            // avoids pulling a JSON dependency into this guard.
            if (resp.statusCode() != 200 || !SUCCESS.matcher(resp.body()).find()) {
                throw new AbuseException("Turnstile verification failed");
            }
        } catch (AbuseException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AbuseException("Turnstile verification error: " + e.getMessage());
        }
    }
}
