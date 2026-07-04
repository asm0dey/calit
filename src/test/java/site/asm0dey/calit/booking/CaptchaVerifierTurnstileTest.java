package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code turnstile} provider path of {@link CaptchaVerifier} end to end against a
 * local stub of the Cloudflare siteverify endpoint (a JDK {@link HttpServer}, no extra dependency).
 * The stub echoes {@code {"success": true}} only when the submitted token is {@code good}, so both
 * the accept and reject branches of {@code verifyTurnstile} are covered without touching the network.
 */
@QuarkusTest
@TestProfile(CaptchaVerifierTurnstileTest.TurnstileOn.class)
class CaptchaVerifierTurnstileTest {

    // Fixed port for the stub; the @TestProfile below points the verifier at it. CI runs in a fresh
    // container so the port is free.
    static final int PORT = 18477;
    static HttpServer server;

    public static class TurnstileOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "calit.captcha.provider", "turnstile",
                    "calit.abuse.turnstile.secret", "test-secret",
                    "calit.abuse.turnstile.verify-url", "http://localhost:" + PORT + "/siteverify");
        }
    }

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/siteverify", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var ok = body.contains("response=good");
            var json = ok ? "{\"success\": true}" : "{\"success\": false, \"error-codes\": []}";
            var bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Inject
    CaptchaVerifier verifier;

    @Test
    void validTurnstileTokenPasses() {
        assertDoesNotThrow(() -> verifier.verify("good", null));
    }

    @Test
    void invalidTurnstileTokenThrows() {
        assertThrows(AbuseException.class, () -> verifier.verify("bad", null));
    }

    @Test
    void missingTurnstileTokenThrows() {
        assertThrows(AbuseException.class, () -> verifier.verify(null, null));
        assertThrows(AbuseException.class, () -> verifier.verify("   ", null));
    }
}
