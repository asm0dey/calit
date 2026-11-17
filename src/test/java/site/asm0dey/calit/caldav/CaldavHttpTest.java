package site.asm0dey.calit.caldav;

import module java.base;
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
    final CountDownLatch stopped = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // the stalling handler blocks; keep it off the dispatcher thread so stop() and other contexts still run
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/stall", x -> {
            x.sendResponseHeaders(200, 0);
            try (var out = x.getResponseBody()) {
                // a body that starts and then never ends, until the test is over
                out.write('x');
                out.flush();
                stopped.await(10, TimeUnit.SECONDS);
            } catch (IOException _) {
                // client hung up at its deadline, as intended
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/redirect", x -> {
            seen.add("redirect");
            x.getResponseHeaders().add("Location", "http://10.0.0.5/secret");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/badloc", x -> {
            x.getResponseHeaders().add("Location", "http://exa mple/");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/huge", x -> {
            x.sendResponseHeaders(200, 0);
            var chunk = new byte[64 * 1024];
            try (var out = x.getResponseBody()) {
                for (int i = 0; i < 200; i++) {
                    out.write(chunk);
                }
            } catch (IOException _) {
                // client hung up at the cap, as intended
            }
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
        stopped.countDown();
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

    @Test
    void oversizedBodyIsRefused() {
        var http = new CaldavHttp(config(true));
        assertThatThrownBy(() -> http.send("u", "p", "GET", url("/huge"), Map.of(), null))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.HTTP_ERROR));
    }

    @Test
    void stalledBodyFailsWithinTheTimeout() {
        // timeout() is 2 s; the server never finishes the body
        var http = new CaldavHttp(config(true));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> http.send("u", "p", "GET", url("/stall"), Map.of(), null))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.UNREACHABLE));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void malformedLocationIsHttpError() {
        var http = new CaldavHttp(config(true));
        assertThatThrownBy(() -> http.send("u", "p", "GET", url("/badloc"), Map.of(), null))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.HTTP_ERROR));
    }

    /**
     * The first connection answers without {@code Connection: close}, so the client pools it, then drops the next
     * request on it unanswered: a server that closed a kept-alive connection (Radicale's HTTP/1.0 does every time).
     */
    @Test
    void idempotentRequestIsRetriedOnAStalePooledConnection() throws Exception {
        var methods = new CopyOnWriteArrayList<String>();
        try (var raw = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            Thread
                .ofVirtual()
                .start(() -> {
                    try (var first = raw.accept()) {
                        methods.add(readRequestLine(first));
                        first.getOutputStream().write("HTTP/1.1 207 Multi-Status\r\nContent-Length: 2\r\n\r\nhi"
                            .getBytes()
                        );
                        methods.add(readRequestLine(first));
                    } catch (IOException _) {
                        // the test fails on the client side
                    }
                    try (var second = raw.accept()) {
                        methods.add(readRequestLine(second));
                        second.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes());
                    } catch (IOException _) {
                        // the test fails on the client side
                    }
                });
            var http = new CaldavHttp(config(true));
            var base = "http://127.0.0.1:" + raw.getLocalPort();

            assertThat(http.send("u", "p", "PROPFIND", URI.create(base + "/cal/"), Map.of(), null).status()).isEqualTo(
                    207
            );
            assertThat(http.send("u", "p", "DELETE", URI.create(base + "/cal/x.ics"), Map.of(), null).status()).isEqualTo(
                    404
            );
            assertThat(methods).containsExactly("PROPFIND", "DELETE", "DELETE");
        }
    }

    /**
     * Reads one request's headers and returns its method.
     */
    static String readRequestLine(Socket socket) throws IOException {
        var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var line = in.readLine();
        for (var h = line; h != null && !h.isEmpty(); h = in.readLine()) {
            // headers
        }
        return line.substring(0, line.indexOf(' '));
    }

    @Test
    void restrictedHeaderIsHttpError() {
        var http = new CaldavHttp(config(true));
        assertThatThrownBy(() -> http.send("u", "p", "GET", url("/ok"), Map.of("Content-Length", "1"), null))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.HTTP_ERROR));
    }
}
