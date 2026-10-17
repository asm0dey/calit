package site.asm0dey.calit.caldav;

import module java.base;
import module java.net.http;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Packaging proof for ical4j (JVM jar here, the native binary under {@code -Dnative}): a real Radicale holds a
 * recurring {@code TZID=Europe/Berlin} event and an Outlook-style {@code TZID=W. Europe Standard Time} event, neither
 * with a VTIMEZONE, and the packaged app must parse, expand and zone-resolve both to keep their times off the public
 * booking page. HTTP only: the app is a separate %prod process, so CSRF is on and the owner is created through
 * {@code /setup} (empty DB) or {@code /signup} (DB left over from the surefire phase).
 */
@QuarkusIntegrationTest
@Tag("integration")
@QuarkusTestResource(value = CaldavFlowIT.Radicale.class, restrictToAnnotatedClass = true)
class CaldavFlowIT {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final String AUTH =
            "Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));

    /**
     * Radicale plus open signup; keeps the server URL for the test JVM (IT config never reaches it).
     */
    public static class Radicale extends RadicaleResource {
        static volatile String url;

        @Override
        public Map<String, String> start() {
            var config = new HashMap<>(super.start());
            url = config.get("caldav.test.url");
            config.put("calit.signup.enabled", "true");
            return config;
        }
    }

    /**
     * Every cookie the app has set so far, csrf-token and quarkus-credential included; sent by hand because the
     * %prod cookies are Secure and this is plain http.
     */
    private final Map<String, String> jar = new HashMap<>();

    private Response get(String path) {
        Response r = given().cookies(jar).redirects().follow(false).get(path);
        jar.putAll(r.getCookies());
        return r;
    }

    private Response post(String path, Map<String, ?> form) {
        Response r = given()
            .cookies(jar)
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParams(form)
            .formParam("csrf-token", jar.get("csrf-token"))
            .post(path);
        jar.putAll(r.getCookies());
        return r;
    }

    private static int dav(String method, String url, String contentType, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var req = HttpRequest
                .newBuilder(URI.create(url))
                .header("Authorization", AUTH)
                .header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static String event(String uid, String tzid, LocalDateTime start, String rrule) {
        return """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//calit//it//EN
                BEGIN:VEVENT
                UID:%s
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=%s:%s
                DTEND;TZID=%s:%s
                %sSUMMARY:Busy
                END:VEVENT
                END:VCALENDAR
                """
            .formatted(uid, tzid, LOCAL.format(start), tzid, LOCAL.format(start.plusHours(1)), rrule)
            .replace("\n", "\r\n");
    }

    private static String utc(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(BERLIN).toInstant().toString();
    }

    @Test
    void caldavBusyTimeBlocksSlotsOnThePublicPage() throws Exception {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var username = "caldavit" + suffix;
        var password = "it-pass-" + suffix;
        // owner: first user on an empty DB, else a self-signup
        if (get("/setup").statusCode() == 200) {
            assertThat(post("/setup", Map.of("username", username, "password", password)).statusCode()).isEqualTo(302);
        } else {
            assertThat(get("/signup").statusCode()).isEqualTo(200);
            assertThat(post("/signup", Map.of("username", username, "password", password)).statusCode()).isEqualTo(303);
        }
        assertThat(post("/j_security_check", Map.of("j_username", username, "j_password", password)).statusCode())
            .isEqualTo(302);
        assertThat(jar).containsKey("quarkus-credential");
        // first-login wizard: Berlin, which also seeds Mon-Fri 09:00-18:00
        assertThat(get("/me/setup").statusCode()).isEqualTo(200);
        assertThat(post(
                "/me/setup",
                Map.of("ownerName", "IT Owner", "ownerEmail", "owner@example.com", "timezone", BERLIN.getId())
        )
            .statusCode()
        )
            .isEqualTo(303);
        // a calendar with two zone-only-by-name events on a weekday a few days out
        var day = LocalDate.now(BERLIN).plusDays(3);
        while (day.getDayOfWeek().getValue() > 5) {
            day = day.plusDays(1);
        }
        var calendar = Radicale.url + "alice/it-" + suffix + "/";
        assertThat(
                dav(
                        "MKCALENDAR",
                        calendar,
                        "application/xml; charset=utf-8",
                        """
                <?xml version="1.0" encoding="utf-8"?>
                <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:set><D:prop>
                    <C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set>
                  </D:prop></D:set>
                </C:mkcalendar>
                """
                )
        )
            .isEqualTo(201);
        // weekly from a week earlier: the busy block on `day` only exists after client-side expansion
        assertThat(
                dav(
                        "PUT",
                        calendar + "berlin.ics",
                        "text/calendar; charset=utf-8",
                        event(
                                "berlin-" + suffix,
                                "Europe/Berlin",
                                day.minusWeeks(1).atTime(10, 0),
                                "RRULE:FREQ=WEEKLY;COUNT=3\r\n"
                        )
                )
        )
            .isEqualTo(201);
        assertThat(
                dav(
                        "PUT",
                        calendar + "outlook.ics",
                        "text/calendar; charset=utf-8",
                        event("outlook-" + suffix, "W. Europe Standard Time", day.atTime(14, 0), "")
                )
        )
            .isEqualTo(201);
        // connect the account and read that calendar for busy time
        assertThat(get("/me/calendars").statusCode()).isEqualTo(200);
        assertThat(post(
                "/me/calendars/caldav",
                Map.of("serverUrl", Radicale.url, "username", "alice", "password", "secret")
        )
            .statusCode()
        )
            .isEqualTo(303);
        var page = get("/me/calendars").then().statusCode(200).extract().asString();
        var row = "value=\"(\\d+:[^\"]*/it-" + suffix + "/)\"";
        assertThat(page).containsPattern(row);
        var matcher = Pattern.compile(row).matcher(page);
        matcher.find();
        assertThat(post("/me/calendars/calendars", Map.of("read", matcher.group(1))).statusCode()).isEqualTo(303);

        assertThat(post(
                "/me/meeting-types",
                Map.of(
                        "name",
                        "CalDAV IT",
                        "slug",
                        "caldav-it",
                        "durationMinutes",
                        "30",
                        "minNoticeMinutes",
                        "0",
                        "horizonDays",
                        "30",
                        "locationType",
                        "PHONE"
                )
        )
            .statusCode()
        )
            .isEqualTo(200);

        var booking = given().get("/" + username + "/caldav-it").then().statusCode(200).extract().asString();
        assertThat(booking)
            .as("booking page, not the calendar-unavailable page")
            .contains("name=\"startUtc\"")
            .contains("value=\"" + utc(day, 9, 30) + "\"", "value=\"" + utc(day, 11, 0) + "\"")
            .contains("value=\"" + utc(day, 13, 30) + "\"", "value=\"" + utc(day, 15, 0) + "\"")
            .doesNotContain("value=\"" + utc(day, 10, 0) + "\"", "value=\"" + utc(day, 10, 30) + "\"")
            .doesNotContain("value=\"" + utc(day, 14, 0) + "\"", "value=\"" + utc(day, 14, 30) + "\"");
    }
}
