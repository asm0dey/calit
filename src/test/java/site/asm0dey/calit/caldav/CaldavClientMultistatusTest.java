package site.asm0dey.calit.caldav;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CaldavAccount;

/**
 * Canned calendar-query answers from a loopback server: anything short of a complete 200 result must fail closed.
 */
class CaldavClientMultistatusTest {
    static final String OK_OBJECT =
            """
            <D:response><D:href>/cal/a.ics</D:href><D:propstat>
            <D:prop><C:calendar-data>BEGIN:VCALENDAR</C:calendar-data></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>""";
    HttpServer server;
    CaldavClient client;
    CaldavAccount account;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serve("/complete", OK_OBJECT);
        serve(
                "/forbidden-object",
                OK_OBJECT
                + """
                <D:response><D:href>/cal/b.ics</D:href><D:propstat>
                <D:prop><C:calendar-data/></D:prop>
                <D:status>HTTP/1.1 403 Forbidden</D:status></D:propstat></D:response>"""
        );
        serve(
                "/truncated",
                OK_OBJECT
                + """
                <D:response><D:href>/cal/</D:href>
                <D:status>HTTP/1.1 507 Insufficient Storage</D:status></D:response>"""
        );
        serve(
                "/bad-href",
                OK_OBJECT
                + """
                <D:response><D:href>http://[bad</D:href><D:propstat>
                <D:prop><C:calendar-data>BEGIN:VCALENDAR</C:calendar-data></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"""
        );
        server.start();
        client = new CaldavClient(new CaldavHttp(CaldavHttpTest.config(true)));
        account = new CaldavAccount();
        account.username = "u";
        account.password = "p";
    }

    void serve(String path, String responses) {
        var body = ("""
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">%s</D:multistatus>"""
            .formatted(responses))
            .getBytes(StandardCharsets.UTF_8);
        server.createContext(path, x -> {
            x.sendResponseHeaders(207, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    List<String> query(String path) {
        var url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        return client.queryEvents(account, url, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
    }

    @Test
    void completeResultIsReturned() {
        assertThat(query("/complete")).containsExactly("BEGIN:VCALENDAR");
    }

    @Test
    void nonOkObjectFailsClosed() {
        assertThatThrownBy(() -> query("/forbidden-object"))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void truncatedResultFailsClosed() {
        assertThatThrownBy(() -> query("/truncated"))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.status()).isEqualTo(507));
    }

    @Test
    void unresolvableHrefFailsClosed() {
        assertThatThrownBy(() -> query("/bad-href")).isInstanceOf(CaldavException.class);
    }
}
