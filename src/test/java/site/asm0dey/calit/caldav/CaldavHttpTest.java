package site.asm0dey.calit.caldav;

import module java.base;
import module java.net.http;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit against a loopback JDK server. The config stub allows private hosts for the first hop only,
 * so the redirect hop is checked with the guard on, and must be refused before anything connects.
 */
class CaldavHttpTest {
    HttpServer server;
    final List<String> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/redirect", x -> {
            seen.add("redirect");
            x.getResponseHeaders().add("Location", "http://10.0.0.5/secret");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/denied", x -> {
            x.sendResponseHeaders(401, -1);
            x.close();
        });
        server.createContext("/ok", x -> {
            var body = "hi".getBytes();
            seen.add(x.getRequestHeaders().getFirst("Authorization"));
            x.sendResponseHeaders(207, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    static CaldavConfig config(boolean... allowByCall) {
        var calls = new AtomicInteger();
        return new CaldavConfig() {
            @Override
            public boolean allowPrivateHosts() {
                int i = calls.getAndIncrement();
                return allowByCall[Math.min(i, allowByCall.length - 1)];
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(2);
            }
        };
    }

    @Test
    void redirectToPrivateHostIsRefused() {
        var http = new CaldavHttp(config(true, false));
        assertThatThrownBy(() -> http.send("u", "p", "PROPFIND", url("/redirect"), Map.of(), null))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.PRIVATE_HOST));
        assertThat(seen).containsExactly("redirect");
    }

    @Test
    void unauthorizedMapsToReason() {
        var http = new CaldavHttp(config(true));
        assertThatThrownBy(() -> http.send("u", "p", "GET", url("/denied"), Map.of(), null))
            .isInstanceOfSatisfying(CaldavException.class, e -> {
                assertThat(e.reason()).isEqualTo(CaldavException.Reason.UNAUTHORIZED);
                assertThat(e.status()).isEqualTo(401);
            });
    }

    @Test
    void sendsBasicAuthAndReturnsBody() {
        var r = new CaldavHttp(config(true)).send("u", "p", "REPORT", url("/ok"), Map.of("Depth", "1"), "<x/>");
        assertThat(r.status()).isEqualTo(207);
        assertThat(r.body()).isEqualTo("hi");
        assertThat(seen).containsExactly("Basic " + Base64.getEncoder().encodeToString("u:p".getBytes()));
    }

    @Test
    void refusesPrivateHostByDefault() {
        var http = new CaldavHttp(config(false));
        assertThatThrownBy(() -> http.send("u", "p", "GET", url("/ok"), Map.of(), null))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.PRIVATE_HOST));
        assertThat(seen).isEmpty();
    }
}
