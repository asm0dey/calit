package site.asm0dey.calit.caldav;

import module java.base;
import module java.net.http;
import jakarta.enterprise.context.ApplicationScoped;
import site.asm0dey.calit.calendar.CaldavAccount;

/**
 * HTTP for CalDAV with Basic auth and the {@link HostGuard} applied to every hop.
 */
@ApplicationScoped
public class CaldavHttp {
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY = 10 * 1024 * 1024;

    public record Response(int status, Map<String, List<String>> headers, String body) {}

    private final CaldavConfig config;
    private final HttpClient client;

    public CaldavHttp(CaldavConfig config) {
        this.config = config;
        this.client = HttpClient
            .newBuilder()
            .connectTimeout(config.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public Response send(CaldavAccount creds, String method, URI uri, Map<String, String> headers, String body) {
        return send(creds.username, creds.password, method, uri, headers, body);
    }

    public Response send(String user, String password, String method, URI uri, Map<String, String> headers, String body) {
        var origin = uri;
        for (int hop = 0; ; hop++) {
            // shortcut: DNS rebinding between check and connect is not prevented, pin the resolved IP via a custom
            // resolver if this instance is multi-tenant and hostile
            uri = HostGuard.checked(uri, config.allowPrivateHosts());
            var res = execute(request(user, password, method, origin, uri, headers, body));
            int status = res.statusCode();
            var location = res.headers().firstValue("Location");
            if (status >= 300 && status < 400 && location.isPresent()) {
                uri = redirect(uri, location.get(), status, hop);
                continue;
            }
            if (status == 401 || status == 403) {
                throw new CaldavException(
                        CaldavException.Reason.UNAUTHORIZED,
                        status,
                        "CalDAV server rejected the credentials",
                        null
                );
            }
            return new Response(status, res.headers().map(), res.body());
        }
    }

    /**
     * Builds the request for {@code uri}, which must be the value {@link HostGuard#checked} just returned.
     */
    private HttpRequest request(
            String user,
            String password,
            String method,
            URI origin,
            URI uri,
            Map<String, String> headers,
            String body
    ) {
        try {
            var req = HttpRequest
                .newBuilder(uri)
                .timeout(config.timeout())
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)
                );
            headers.forEach(req::header);
            // never hand the credentials to a different host than the one the user entered
            if (sameOrigin(origin, uri)) {
                var basic = Base64
                    .getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
                req.header("Authorization", "Basic " + basic);
            }
            return req.build();
        } catch (IllegalArgumentException e) {
            throw new CaldavException(CaldavException.Reason.HTTP_ERROR, 0, "Invalid CalDAV request", e);
        }
    }

    /**
     * Sends and reads the whole response under one deadline: JDK 25's request timeout stops at the headers, so a server
     * dripping its body would otherwise hold the caller's thread. Bodies over {@link #MAX_BODY} bytes are refused.
     */
    private HttpResponse<String> execute(HttpRequest request) {
        var future = client.sendAsync(
                request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), MAX_BODY)
        );
        try {
            return future.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CaldavException(CaldavException.Reason.UNREACHABLE, 0, "CalDAV server too slow", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CaldavException(CaldavException.Reason.UNREACHABLE, 0, "Interrupted", e);
        } catch (ExecutionException e) {
            throw failure(e.getCause());
        }
    }

    private static CaldavException failure(Throwable cause) {
        // shortcut: JDK's LimitingSubscriber signals overflow only by message; oversizedBodyIsRefused pins it, swap for a counting subscriber if a JDK changes it
        if (cause instanceof IOException io
                && io.getMessage() != null
                && io.getMessage().startsWith("body exceeds capacity")) {
            return new CaldavException(CaldavException.Reason.HTTP_ERROR, 0, "CalDAV response too large", io);
        }
        if (cause instanceof IOException io) {
            return new CaldavException(CaldavException.Reason.UNREACHABLE, 0, "CalDAV server unreachable", io);
        }
        return new CaldavException(CaldavException.Reason.HTTP_ERROR, 0, "CalDAV request failed", cause);
    }

    /**
     * The next hop's (not yet guarded) target, or a refusal once {@link #MAX_REDIRECTS} hops were followed.
     */
    private static URI redirect(URI uri, String location, int status, int hop) {
        if (hop >= MAX_REDIRECTS) {
            throw new CaldavException(CaldavException.Reason.HTTP_ERROR, status, "Too many redirects", null);
        }
        try {
            return uri.resolve(location);
        } catch (IllegalArgumentException e) {
            throw new CaldavException(CaldavException.Reason.HTTP_ERROR, status, "Malformed redirect", e);
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && a.getPort() == b.getPort();
    }
}
