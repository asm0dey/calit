package site.asm0dey.calit.notify;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The channel list is plain HTML with a plain POST: no JavaScript path is required, and the page
 * always renders one empty row so a host without JS can add one channel per save.
 */
@QuarkusTest
class ChannelSettingsPageTest {

    private static final String TELEGRAM = "telegram://111:AAbbCC/222333";

    private static final String SLACK = "slack://T00/B00/xxxx";

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
        String redacted = given().when()
                .get("/me/settings")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
                .lines()
                .filter(l -> l.contains("name=\"channelUrl\"") && l.contains("value=\"telegram"))
                .findFirst()
                .orElseThrow()
                .replaceAll(".*value=\"([^\"]+)\".*", "$1");

        given().contentType("application/x-www-form-urlencoded")
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
