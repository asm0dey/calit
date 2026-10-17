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
            HttpRequest request;
            try {
                var req = HttpRequest
                    .newBuilder(uri)
                    .timeout(config.timeout())
                    .method(
                            method,
                            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(
                                    body
                            )
                    );
                headers.forEach(req::header);
                // never hand the credentials to a different host than the one the user entered
                if (sameOrigin(origin, uri)) {
                    var basic =
                            Base64
                        .getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
                    req.header("Authorization", "Basic " + basic);
                }
                request = req.build();
            } catch (IllegalArgumentException e) {
                throw new CaldavException(CaldavException.Reason.HTTP_ERROR, 0, "Invalid CalDAV request", e);
            }
            HttpResponse<InputStream> res;
            String text;
            try {
                res = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                text = readCapped(res);
            } catch (IOException e) {
                throw new CaldavException(CaldavException.Reason.UNREACHABLE, 0, "CalDAV server unreachable", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CaldavException(CaldavException.Reason.UNREACHABLE, 0, "Interrupted", e);
            }
            int status = res.statusCode();
            var location = res.headers().firstValue("Location");
            if (status >= 300 && status < 400 && location.isPresent()) {
                if (hop >= MAX_REDIRECTS) {
                    throw new CaldavException(CaldavException.Reason.HTTP_ERROR, status, "Too many redirects", null);
                }
                try {
                    uri = uri.resolve(location.get());
                } catch (IllegalArgumentException e) {
                    throw new CaldavException(CaldavException.Reason.HTTP_ERROR, status, "Malformed redirect", e);
                }
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
            return new Response(status, res.headers().map(), text);
        }
    }

    /**
     * Reads at most {@link #MAX_BODY} bytes; a longer body is abandoned (stream closed) and refused.
     */
    private static String readCapped(HttpResponse<InputStream> res) throws IOException {
        try (var in = res.body()) {
            var bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new CaldavException(
                        CaldavException.Reason.HTTP_ERROR,
                        res.statusCode(),
                        "CalDAV response too large",
                        null
                );
            }
            return new String(bytes, charset(res));
        }
    }

    private static Charset charset(HttpResponse<?> res) {
        return res
            .headers()
            .firstValue("Content-Type")
            .map(ct -> Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE).matcher(ct))
            .filter(Matcher::find)
            .map(m -> {
                try {
                    return Charset.forName(m.group(1));
                } catch (IllegalArgumentException _) {
                    return StandardCharsets.UTF_8;
                }
            })
            .orElse(StandardCharsets.UTF_8);
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && a.getPort() == b.getPort();
    }
}
