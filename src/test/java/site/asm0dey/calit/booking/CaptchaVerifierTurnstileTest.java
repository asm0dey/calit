package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code turnstile} provider path of {@link CaptchaVerifier} end to end against a
 * local stub of the Cloudflare siteverify endpoint (a JDK {@link HttpServer}, no extra dependency).
 * The stub echoes {@code {"success": true}} only when the submitted token is {@code good}, so both
 * the accept and reject branches of {@code verifyTurnstile} are covered without touching the network.
 * Runs in the default boot: the config bean is spied to select the turnstile provider + point the
 * verifier at the local stub, instead of a @TestProfile restart.
 */
@QuarkusTest
class CaptchaVerifierTurnstileTest {

    /** Ephemeral: bound to 0 and read back in {@link #startStub()}, so nothing on the box can collide. */
    static int port;

    static HttpServer server;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
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
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Inject
    CaptchaVerifier verifier;

    @InjectSpy
    CaptchaProviderConfig providerConfig;

    @BeforeEach
    void enableTurnstile() {
        when(providerConfig.provider()).thenReturn("turnstile");
        when(providerConfig.turnstileSecret()).thenReturn(Optional.of("test-secret"));
        when(providerConfig.turnstileVerifyUrl()).thenReturn("http://localhost:" + port + "/siteverify");
    }

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
