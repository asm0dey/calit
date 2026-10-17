package site.asm0dey.calit.caldav;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarListPort.RemoteCalendar;

@QuarkusTest
@QuarkusTestResource(value = RadicaleResource.class, restrictToAnnotatedClass = true)
class CaldavClientTest {
    @Inject
    CaldavClient client;
    @Inject
    CaldavHttp http;
    // default: test classes are validated on every boot, including ones without RadicaleResource
    @ConfigProperty(name = "caldav.test.url", defaultValue = "unset")
    String baseUrl;

    CaldavAccount account() {
        var d = client.discover(baseUrl, "alice", "secret");
        var a = new CaldavAccount();
        a.serverUrl = baseUrl;
        a.username = "alice";
        a.password = "secret";
        a.principalUrl = d.principalUrl().toString();
        a.homeUrl = d.homeUrl().toString();
        return a;
    }

    /**
     * MKCALENDAR a fresh collection under the home; returns its absolute URL.
     */
    URI mkcalendar(CaldavAccount a, String name, String component) {
        var url = URI.create(a.homeUrl).resolve(name + "/");
        var body = """
                <?xml version="1.0" encoding="utf-8"?>
                <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:set><D:prop>
                    <D:displayname>%s</D:displayname>
                    <C:supported-calendar-component-set><C:comp name="%s"/></C:supported-calendar-component-set>
                  </D:prop></D:set>
                </C:mkcalendar>
                """
            .formatted(name.toUpperCase(Locale.ROOT), component);
        var res = http.send(a, "MKCALENDAR", url, Map.of("Content-Type", "application/xml; charset=utf-8"), body);
        assertThat(res.status()).isEqualTo(201);
        return url;
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void discoversFromBaseUrl() {
        var d = client.discover(baseUrl, "alice", "secret");
        assertThat(d.principalUrl().getPath()).isEqualTo("/alice/");
        assertThat(d.homeUrl().getPath()).isEqualTo("/alice/");
        assertThat(d.homeUrl().isAbsolute()).isTrue();
    }

    @Test
    void refusesUnsafeServerUrls() {
        for (var url :
                List.of(
                        "https://user:pass@host/",
                        "ftp://host/",
                        "host/no-scheme",
                        "https://host/?q=1",
                        "https://host/#frag",
                        "https:///nohost"
        )) {
            assertThatThrownBy(() -> client.discover(url, "alice", "secret"))
                .isInstanceOfSatisfying(CaldavException.class, e -> {
                    assertThat(e.reason()).isEqualTo(CaldavException.Reason.NOT_CALDAV);
                    assertThat(e.getMessage()).doesNotContain("pass");
                });
        }
    }

    @Test
    void normalizesServerUrl() {
        assertThat(CaldavClient.serverUri(" HTTPS://Host.Example/./dav/../dav/ ").toASCIIString())
            .isEqualTo("HTTPS://Host.Example/dav/");
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        assertThatThrownBy(() -> client.discover(baseUrl, "alice", "nope"))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.UNAUTHORIZED));
    }

    @Test
    void listsOnlyEventCalendars() {
        var a = account();
        var events = mkcalendar(a, unique("events"), "VEVENT");
        var tasks = mkcalendar(a, unique("tasks"), "VTODO");

        var listed = client.listCalendars(a);

        assertThat(listed)
            .extracting(RemoteCalendar::remoteId)
            .contains(events.toString())
            .doesNotContain(tasks.toString(), a.homeUrl);
        assertThat(listed)
            .filteredOn(c -> c.remoteId().equals(events.toString()))
            .singleElement()
            .satisfies(c -> {
                assertThat(c.summary()).startsWith("EVENTS-");
                assertThat(c.meetSupported()).isFalse();
            });
    }

    @Test
    void putQueryGetDeleteRoundTrip() {
        var a = account();
        var cal = mkcalendar(a, unique("rt"), "VEVENT");
        var eventUrl = cal.resolve("booking-1.ics");
        var ics =
                """
                BEGIN:VCALENDAR\r
                VERSION:2.0\r
                PRODID:-//calit//test//EN\r
                BEGIN:VEVENT\r
                UID:booking-1@calit\r
                DTSTAMP:20261001T000000Z\r
                DTSTART:20261103T100000Z\r
                DTEND:20261103T110000Z\r
                SUMMARY:Round trip\r
                END:VEVENT\r
                END:VCALENDAR\r
                """;

        client.create(a, eventUrl, ics);

        var found = client.queryEvents(
                a,
                cal.toString(),
                Instant.parse("2026-11-02T00:00:00Z"),
                Instant.parse("2026-11-09T00:00:00Z")
        );
        assertThat(found).singleElement().asString().contains("UID:booking-1@calit");
        assertThat(client.queryEvents(
                a,
                cal.toString(),
                Instant.parse("2026-12-01T00:00:00Z"),
                Instant.parse("2026-12-08T00:00:00Z")
        ))
            .isEmpty();

        var fetched = client.get(a, eventUrl);
        assertThat(fetched.ics()).contains("SUMMARY:Round trip");
        assertThat(fetched.etag()).isNotBlank();
        // If-None-Match: * refuses to clobber; If-Match with the current ETag updates
        assertThatThrownBy(() -> client.create(a, eventUrl, ics)).isInstanceOf(CaldavException.class);
        var newEtag = client.update(a, eventUrl, ics.replace("Round trip", "Updated"), fetched.etag());
        assertThat(client.get(a, eventUrl).ics()).contains("SUMMARY:Updated");
        if (newEtag != null) {
            assertThat(newEtag).isNotEqualTo(fetched.etag());
        }

        client.delete(a, eventUrl);
        assertThat(client.get(a, eventUrl)).isNull();
    }

    @Test
    void deleteMissingIsSuccess() {
        var a = account();
        var cal = mkcalendar(a, unique("del"), "VEVENT");
        var missing = cal.resolve("nope.ics");
        assertThatCode(() -> client.delete(a, missing)).doesNotThrowAnyException();
    }
}
