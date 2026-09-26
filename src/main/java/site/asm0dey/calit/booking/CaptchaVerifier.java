package site.asm0dey.calit.booking;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.altcha.altcha.v1.Altcha;

/**
 * Server-side CAPTCHA verification. The active provider ({@code none} | {@code turnstile} |
 * {@code altcha}) is resolved by {@link CaptchaProviderConfig}. {@code none} is a no-op success
 * (local dev/tests never call an external service). Failures throw {@link AbuseException} (HTTP 400).
 */
@ApplicationScoped
public class CaptchaVerifier {
    final CaptchaProviderConfig providerConfig;

    @Inject
    public CaptchaVerifier(CaptchaProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }

    // SEC-SSRF-01: bound the synchronous booking-path call so a hung upstream can't pin a thread.
    private final HttpClient http = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    /**
     * Matches the success flag in the siteverify JSON, tolerating whitespace: {@code "success" : true}.
     */
    private static final Pattern SUCCESS = Pattern.compile("\"success\"\\s*:\\s*true");

    /**
     * Enforces the active provider. Throws AbuseException (400) when the presented token is invalid.
     */
    public void verify(String turnstileToken, String altchaSolution) {
        switch (providerConfig.provider()) {
            case "turnstile" -> verifyTurnstile(turnstileToken);
            case "altcha" -> verifyAltcha(altchaSolution);
            default -> {
                /* none: no-op success */
            }
        }
    }

    private void verifyAltcha(String solution) {
        if (solution == null || solution.isBlank()) {
            throw new AbuseException("Missing ALTCHA solution");
        }
        try {
            boolean ok = Altcha.verifySolution(solution, providerConfig.altchaHmacKey().orElse(""), true);
            if (!ok) {
                throw new AbuseException("ALTCHA verification failed");
            }
        } catch (AbuseException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AbuseException("ALTCHA verification error: " + e.getMessage());
        }
    }

    private void verifyTurnstile(String token) {
        if (token == null || token.isBlank()) {
            throw new AbuseException("Missing Turnstile token");
        }
        try {
            var body = "secret="
                    + URLEncoder.encode(providerConfig.turnstileSecret().orElse(""), StandardCharsets.UTF_8)
                    + "&response="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8);
            var req = HttpRequest
                .newBuilder(URI.create(providerConfig.turnstileVerifyUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || !SUCCESS.matcher(resp.body()).find()) {
                throw new AbuseException("Turnstile verification failed");
            }
        } catch (AbuseException ae) {
            throw ae;
        } catch (InterruptedException e) {
            // Preserve the interrupt status for the caller; the booking still fails closed.
            Thread.currentThread().interrupt();
            throw new AbuseException("Turnstile verification interrupted");
        } catch (Exception e) {
            throw new AbuseException("Turnstile verification error: " + e.getMessage());
        }
    }
}
