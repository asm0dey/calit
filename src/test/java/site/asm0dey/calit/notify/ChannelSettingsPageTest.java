package site.asm0dey.calit.notify;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The channel list is plain HTML with a plain POST: no JavaScript path is required, and the page
 * always renders one empty row so a host without JS can add one channel per save. An unchecked
 * checkbox submits nothing, so the {@code channelDefault} field arrives as the set of ids that ARE
 * ticked — which is why a row omitted from it is turned off rather than left alone.
 */
@QuarkusTest
class ChannelSettingsPageTest {

    private static final String TELEGRAM = "telegram://111:AAbbCC/222333";

    private static final String SLACK = "slack://T00/B00/xxxx";

    /** Ephemeral port, per the repo rule against hardcoded ones (calit-dmcn). */
    static int port;

    static HttpServer server;

    static final AtomicInteger hits = new AtomicInteger();

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
    }

    // S8786: a compiled find() pattern instead of replaceAll's backtracking-prone ".*x.*" regex.
    private static final Pattern VALUE_ATTR = Pattern.compile("value=\"([^\"]+)\"");

    private static String extractValue(String line) {
        var m = VALUE_ATTR.matcher(line);
        assertTrue(m.find(), "no value attribute found: " + line);
        return m.group(1);
    }

    private static List<NotificationChannel> channelsOf(long ownerId) {
        return QuarkusTransaction.requiringNew().call(() -> NotificationChannel.forOwner(ownerId));
    }

    private static Long seed(long ownerId, String url, String label) {
        return QuarkusTransaction.requiringNew().call(() -> MultiHostFixtures.channel(ownerId, url, label).id);
    }

    /** A second owner needs its own {@code app_user} row: V32's owner_id FK rejects a dangling owner. */
    private static long otherOwner() {
        return QuarkusTransaction.requiringNew().call(() -> MultiHostFixtures.enabledUser("other-owner").id);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void settingsPageAlwaysOffersAnEmptyRow() {
        given().when().get("/me/settings").then().statusCode(200).body(containsString("name=\"channelUrl\""));
    }

    /**
     * Send test runs on the URL as typed, so the empty row can be verified BEFORE it is stored --
     * which is the whole point of the button, since last_failure_at is by definition after the fact.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void sendTestDeliversATypedUrlAndStoresNothing() {
        var before = hits.get();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelUrl", "ntfy+http://localhost:" + port + "/unsaved")
                .formParam("testIndex", "0")
                .when()
                .post("/me/settings/channels/test")
                .then()
                .statusCode(200);

        assertEquals(before + 1, hits.get(), "the typed URL was actually delivered to");
        assertTrue(channelsOf(1L).isEmpty(), "a test must not persist the channel");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void sendTestOnAnUnsupportedTypedUrlReportsTheRejectionAndStoresNothing() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelUrl", "carrier-pigeon://nope")
                .formParam("testIndex", "0")
                .when()
                .post("/me/settings/channels/test")
                .then()
                .statusCode(200);

        assertTrue(channelsOf(1L).isEmpty());
    }

    /** An untouched mask on a saved row still means "use the stored secret", test included. */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void sendTestOnAnUntouchedRowUsesTheStoredSecret() {
        var url = "ntfy+http://localhost:" + port + "/saved";
        var id = seed(1L, url, "Phone");
        var masked = given().when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
                .lines()
                .filter(l -> l.contains("name=\"channelUrl\"") && l.contains("value=\"ntfy"))
                .findFirst()
                .map(ChannelSettingsPageTest::extractValue)
                .orElseThrow();
        var before = hits.get();

        given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("channelId", String.valueOf(id))
                .formParam("channelUrl", masked)
                .formParam("testIndex", "0")
                .when()
                .post("/me/settings/channels/test")
                .then()
                .statusCode(200);

        assertEquals(before + 1, hits.get(), "the mask resolved back to the stored URL");
        assertEquals(url, channelsOf(1L).getFirst().url, "and the stored URL is untouched");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void aNewChannelJoinsTheInheritSet() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelLabel", "Pager")
                .formParam("channelUrl", SLACK)
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200);

        assertTrue(channelsOf(1L).getFirst().defaultEnabled, "a brand-new channel is created enabled");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void untickingTheDefaultBoxKeepsTheChannelButLeavesTheInheritSet() {
        var id = seed(1L, SLACK, "Pager");

        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", String.valueOf(id))
                .formParam("channelLabel", "Pager")
                .formParam("channelUrl", "slack://T00/B00/xxxx")
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200);

        var saved = channelsOf(1L);
        assertEquals(1, saved.size(), "unticking is not deleting");
        assertFalse(saved.getFirst().defaultEnabled);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void tickingTheDefaultBoxPutsTheChannelBackInTheInheritSet() {
        var id = seed(1L, SLACK, "Pager");
        QuarkusTransaction.requiringNew().run(() -> {
            NotificationChannel c = NotificationChannel.findById(id);
            c.defaultEnabled = false;
        });

        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", String.valueOf(id))
                .formParam("channelLabel", "Pager")
                .formParam("channelUrl", "slack://T00/B00/xxxx")
                .formParam("channelDefault", String.valueOf(id))
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200);

        assertTrue(channelsOf(1L).getFirst().defaultEnabled);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void savingAChannelStoresItAndRendersItRedacted() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelLabel", "")
                .formParam("channelUrl", TELEGRAM)
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200)
                .body(not(containsString("AAbbCC")));

        var saved = channelsOf(1L);
        assertEquals(1, saved.size());
        assertEquals(TELEGRAM, saved.getFirst().url, "the real URL is stored");
        assertEquals("Telegram", saved.getFirst().label, "a blank label defaults to the display name");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void resubmittingTheRedactedValueKeepsTheStoredSecret() {
        var id = seed(1L, TELEGRAM, "Phone");
        String line = given().when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
                .lines()
                .filter(l -> l.contains("name=\"channelUrl\"") && l.contains("value=\"telegram"))
                .findFirst()
                .orElseThrow();
        var redacted = extractValue(line);

        given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("channelId", String.valueOf(id))
                .formParam("channelLabel", "Phone")
                .formParam("channelUrl", redacted)
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200);

        assertEquals(TELEGRAM, channelsOf(1L).getFirst().url, "an untouched redacted value must not overwrite");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void anUnsupportedUrlIsRejectedAndNothingIsStored() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelLabel", "")
                .formParam("channelUrl", "carrier-pigeon://nope")
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200)
                .body(containsString("supported"));

        assertTrue(channelsOf(1L).isEmpty());
    }

    /**
     * A mask PARSES as a channel (notify4j's {@code tryParse("telegram://…")} yields a
     * ParsedChannel), so without the marker guard a pasted mask would store a dead channel.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void aPastedMaskIsRejectedInANewRow() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelLabel", "")
                .formParam("channelUrl", "telegram://…")
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200)
                .body(containsString("supported"));

        assertTrue(channelsOf(1L).isEmpty(), "a redaction marker must never be stored as a URL");
    }

    /**
     * The counter-case that keeps the mask guard from being over-broad: {@code webhook://example.com}
     * has no path, so notify4j redacts it to ITSELF — an {@code url.equals(redact(url))} guard would
     * refuse a perfectly good channel. The marker guard must let it through.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void aPathlessUrlThatRedactsToItselfStillSaves() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("channelId", "")
                .formParam("channelLabel", "")
                .formParam("channelUrl", "webhook://example.com")
                .when()
                .post("/me/settings/channels")
                .then()
                .statusCode(200);

        var saved = channelsOf(1L);
        assertEquals(1, saved.size(), "a path-less webhook URL is a legitimate channel");
        assertEquals("webhook://example.com", saved.getFirst().url);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void anotherOwnersChannelIsNeverRendered() {
        var other = otherOwner();
        seed(other, SLACK, "Theirs");

        given().when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .body(not(containsString("Theirs")))
                .body(not(containsString("value=\"slack")));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void deleteRemovesOnlyYourOwnChannel() {
        var other = otherOwner();
        var mine = seed(1L, TELEGRAM, "Mine");
        var theirs = seed(other, SLACK, "Theirs");

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/settings/channels/" + theirs + "/delete")
                .then()
                .statusCode(200);
        assertEquals(1, channelsOf(other).size(), "another owner's channel must survive");

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/settings/channels/" + mine + "/delete")
                .then()
                .statusCode(200);
        assertTrue(channelsOf(1L).isEmpty());
    }
}
