package site.asm0dey.calit.caldav;

import module java.base;
import module java.xml;
import jakarta.enterprise.context.ApplicationScoped;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarListPort.RemoteCalendar;

/**
 * The handful of WebDAV/CalDAV calls calit needs, over {@link CaldavHttp} (which owns the SSRF guard and body cap).
 */
@ApplicationScoped
public class CaldavClient {
    private static final String DAV = "DAV:";
    private static final String CALDAV = "urn:ietf:params:xml:ns:caldav";
    private static final String XML = "application/xml; charset=utf-8";
    private static final String PRINCIPAL_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:current-user-principal/></D:prop></D:propfind>""";
    private static final String HOME_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">\
            <D:prop><C:calendar-home-set/></D:prop></D:propfind>""";
    private static final String LIST_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">\
            <D:prop><D:resourcetype/><D:displayname/><C:supported-calendar-component-set/></D:prop></D:propfind>""";
    private static final String QUERY_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">\
            <D:prop><C:calendar-data/></D:prop>\
            <C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT">\
            <C:time-range start="%s" end="%s"/>\
            </C:comp-filter></C:comp-filter></C:filter></C:calendar-query>""";
    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public record Discovery(URI principalUrl, URI homeUrl) {}

    public record Fetched(String ics, String etag) {}

    /**
     * One {@code DAV:response}: its href resolved against the request URI, the props reported with 200, and the
     * first non-200 status in it (response-level or propstat), 0 when there is none.
     */
    private record DavResponse(URI href, List<Element> props, int failedStatus) {
        Optional<Element> prop(String ns, String local) {
            return props
                .stream()
                .flatMap(p -> children(p, ns, local).stream())
                .findFirst();
        }
    }

    private final CaldavHttp http;

    public CaldavClient(CaldavHttp http) {
        this.http = http;
    }

    /**
     * RFC 6764: the URL as given, then {@code /.well-known/caldav}; principal, then calendar home.
     */
    public Discovery discover(String serverUrl, String user, String password) {
        URI base;
        try {
            base = new URI(serverUrl.strip());
        } catch (URISyntaxException e) {
            throw new CaldavException(CaldavException.Reason.NOT_CALDAV, 0, "Not a valid URL", e);
        }
        for (var candidate : List.of(base, base.resolve("/.well-known/caldav"))) {
            var principal = propfind(user, password, candidate, "0", PRINCIPAL_BODY)
                .stream()
                .flatMap(r -> r
                    .prop(DAV, "current-user-principal")
                    .flatMap(p -> href(p, r.href()))
                    .stream())
                .findFirst();
            if (principal.isEmpty()) {
                continue;
            }
            var home = propfind(user, password, principal.get(), "0", HOME_BODY)
                .stream()
                .flatMap(r -> r
                    .prop(CALDAV, "calendar-home-set")
                    .flatMap(p -> href(p, r.href()))
                    .stream())
                .findFirst();
            if (home.isPresent()) {
                return new Discovery(principal.get(), home.get());
            }
        }
        throw new CaldavException(CaldavException.Reason.NOT_CALDAV, "No CalDAV principal found at " + serverUrl);
    }

    /**
     * Calendars under the home that can hold events.
     */
    public List<RemoteCalendar> listCalendars(CaldavAccount a) {
        var home = URI.create(a.homeUrl);
        var res = http.send(a, "PROPFIND", home, Map.of("Depth", "1", "Content-Type", XML), LIST_BODY);
        expectMultistatus(res);
        return parse(res.body(), home)
            .stream()
            .filter(r -> r
                .prop(DAV, "resourcetype")
                .map(t -> !children(t, CALDAV, "calendar").isEmpty())
                .orElse(false))
            .filter(r -> r
                .prop(CALDAV, "supported-calendar-component-set")
                .map(CaldavClient::allowsEvents)
                .orElse(true))
            .map(r -> new RemoteCalendar(r.href().toString(), summary(r), false))
            .toList();
    }

    /**
     * Raw {@code calendar-data} of every event overlapping [from, to); recurrences are expanded by the caller.
     */
    public List<String> queryEvents(CaldavAccount a, String calendarHref, Instant from, Instant to) {
        var uri = URI.create(calendarHref);
        var body = QUERY_BODY.formatted(UTC_STAMP.format(from), UTC_STAMP.format(to));
        var res = http.send(a, "REPORT", uri, Map.of("Depth", "1", "Content-Type", XML), body);
        expectMultistatus(res);
        var responses = parse(res.body(), uri);
        // fail closed: a forbidden, missing or truncated (507) part would silently drop busy time
        var failed = responses
            .stream()
            .mapToInt(DavResponse::failedStatus)
            .filter(s -> s != 0)
            .findFirst();
        if (failed.isPresent()) {
            throw new CaldavException(
                    CaldavException.Reason.HTTP_ERROR,
                    failed.getAsInt(),
                    "CalDAV calendar-query incomplete: HTTP " + failed.getAsInt(),
                    null
            );
        }
        return responses
            .stream()
            .flatMap(r -> r.prop(CALDAV, "calendar-data").stream())
            .map(Element::getTextContent)
            .filter(s -> !s.isBlank())
            .toList();
    }

    /**
     * Creates ({@code ifMatch == null}, refusing to overwrite) or updates the event; returns the new ETag or null.
     */
    public String put(CaldavAccount a, URI eventUrl, String ics, String ifMatch) {
        var headers = new HashMap<String, String>();
        headers.put("Content-Type", "text/calendar; charset=utf-8");
        if (ifMatch == null) {
            headers.put("If-None-Match", "*");
        } else {
            headers.put("If-Match", ifMatch);
        }
        var res = http.send(a, "PUT", eventUrl, headers, ics);
        if (res.status() / 100 != 2) {
            throw httpError(res, "PUT");
        }
        return header(res, "ETag");
    }

    /**
     * The event and its ETag, or null when it is gone.
     */
    public Fetched get(CaldavAccount a, URI eventUrl) {
        var res = http.send(a, "GET", eventUrl, Map.of(), null);
        if (res.status() == 404 || res.status() == 410) {
            return null;
        }
        if (res.status() / 100 != 2) {
            throw httpError(res, "GET");
        }
        return new Fetched(res.body(), header(res, "ETag"));
    }

    /**
     * Deletes the event; already gone counts as done.
     */
    public void delete(CaldavAccount a, URI eventUrl) {
        var res = http.send(a, "DELETE", eventUrl, Map.of(), null);
        if (res.status() / 100 != 2 && res.status() != 404 && res.status() != 410) {
            throw httpError(res, "DELETE");
        }
    }

    /**
     * PROPFIND used during discovery: anything but a parseable 207 means "not here", so the next candidate is tried.
     */
    private List<DavResponse> propfind(String user, String password, URI uri, String depth, String body) {
        var res = http.send(user, password, "PROPFIND", uri, Map.of("Depth", depth, "Content-Type", XML), body);
        if (res.status() != 207) {
            return List.of();
        }
        try {
            return parse(res.body(), uri);
        } catch (CaldavException _) {
            return List.of();
        }
    }

    private static void expectMultistatus(CaldavHttp.Response res) {
        if (res.status() != 207) {
            throw httpError(res, "multistatus");
        }
    }

    private static CaldavException httpError(CaldavHttp.Response res, String what) {
        return new CaldavException(
                CaldavException.Reason.HTTP_ERROR,
                res.status(),
                "CalDAV " + what + " failed with HTTP " + res.status(),
                null
        );
    }

    private static String header(CaldavHttp.Response res, String name) {
        return res
            .headers()
            .entrySet()
            .stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty())
            .map(e -> e.getValue().getFirst())
            .findFirst()
            .orElse(null);
    }

    private static boolean allowsEvents(Element componentSet) {
        return children(componentSet, CALDAV, "comp")
            .stream()
            .anyMatch(c -> "VEVENT".equalsIgnoreCase(c.getAttribute("name")));
    }

    private static String summary(DavResponse r) {
        var name = r.prop(DAV, "displayname").map(Element::getTextContent).map(String::strip).orElse("");
        if (!name.isEmpty()) {
            return name;
        }
        var path = r.href().getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Optional<URI> href(Element parent, URI base) {
        return children(parent, DAV, "href")
            .stream()
            .findFirst()
            .flatMap(h -> resolve(base, h.getTextContent()));
    }

    private static Optional<URI> resolve(URI base, String href) {
        try {
            return Optional.of(base.resolve(href.strip()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static List<DavResponse> parse(String xml, URI base) {
        Document doc;
        try {
            doc = secureBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new CaldavException(CaldavException.Reason.HTTP_ERROR, 207, "Malformed CalDAV response", e);
        }
        var out = new ArrayList<DavResponse>();
        var responses = doc.getElementsByTagNameNS(DAV, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            var response = (Element) responses.item(i);
            var href =
                    children(response, DAV, "href")
                .stream()
                .findFirst()
                .flatMap(h -> resolve(base, h.getTextContent()));
            if (href.isEmpty()) {
                // fail closed: skipping it would silently drop that response's calendar-data (busy time)
                throw new CaldavException(
                        CaldavException.Reason.HTTP_ERROR,
                        207,
                        "CalDAV response without a usable href",
                        null
                );
            }
            var propstats = children(response, DAV, "propstat");
            var props = propstats
                .stream()
                .filter(ps -> children(ps, DAV, "status")
                    .stream()
                    .allMatch(s -> statusCode(s) == 200))
                .flatMap(ps -> children(ps, DAV, "prop").stream())
                .toList();
            var failed = Stream
                .concat(
                        children(response, DAV, "status").stream(),
                        propstats
                            .stream()
                            .flatMap(ps -> children(ps, DAV, "status").stream())
                )
                .mapToInt(CaldavClient::statusCode)
                .filter(c -> c != 200)
                .findFirst()
                .orElse(0);
            out.add(new DavResponse(href.get(), props, failed));
        }
        return out;
    }

    /**
     * The code in a {@code DAV:status} line such as "HTTP/1.1 404 Not Found"; -1 when unreadable.
     */
    private static int statusCode(Element status) {
        var parts = status.getTextContent().strip().split("\\s+");
        try {
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private static List<Element> children(Element parent, String ns, String local) {
        var out = new ArrayList<Element>();
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Namespace-aware, no DTDs, no external entities: the server is tenant-controlled.
     */
    private static DocumentBuilder secureBuilder() throws ParserConfigurationException {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
