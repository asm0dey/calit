package site.asm0dey.calit.notify;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * The override is per (host, meeting type): host 1 narrowing a co-hosted type must not change what
 * host 2 receives. Exercised through the HTTP layer, because the per-host scoping lives in the
 * handler's choice of "which channels are mine".
 */
@QuarkusTest
class ChannelOverrideTest {

    private static final long HOST_A = 1L;
    private static final long HOST_B = 2L;

    @Inject
    ChannelRouter router;

    private Long channel(long ownerId, String label) {
        return QuarkusTransaction.requiringNew()
                .call(() -> MultiHostFixtures.channel(ownerId, "ntfy+http://localhost:1/" + label, label).id);
    }

    /**
     * Seeds HOST_B's app_user row (notification_channel.owner_id carries a real FK), so this must
     * run before any HOST_B-owned channel is persisted.
     */
    private MeetingType sharedType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost");
            MultiHostFixtures.settings(HOST_A, "Creator");
            MultiHostFixtures.settings(HOST_B, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(HOST_A, HOST_B, "override-me", 30, false);
        });
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void customModeNarrowsOnlyTheActingHost() {
        MeetingType type = sharedType();
        var a1 = channel(HOST_A, "a-phone");
        channel(HOST_A, "a-slack");
        channel(HOST_B, "b-phone");
        channel(HOST_B, "b-slack");

        given().contentType("application/x-www-form-urlencoded")
                .formParam("mode", "custom")
                .formParam("channelIds", String.valueOf(a1))
                .when()
                .post("/me/meeting-types/" + type.id + "/notifications")
                .then()
                .statusCode(200);

        assertEquals(1, router.channelsFor(HOST_A, type.id).size(), "creator narrowed");
        assertEquals(2, router.channelsFor(HOST_B, type.id).size(), "co-host still inherits");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void allModeClearsTheOverride() {
        MeetingType type = sharedType();
        var a1 = channel(HOST_A, "a-phone");
        channel(HOST_A, "a-slack");
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(type.id, List.of(a1), List.of(a1)));
        assertEquals(1, router.channelsFor(HOST_A, type.id).size());

        given().contentType("application/x-www-form-urlencoded")
                .formParam("mode", "all")
                .when()
                .post("/me/meeting-types/" + type.id + "/notifications")
                .then()
                .statusCode(200);

        assertEquals(2, router.channelsFor(HOST_A, type.id).size(), "back to inheriting everything");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user"})
    void customWithNoChannelIsRejected() {
        MeetingType type = sharedType();
        var a1 = channel(HOST_A, "a-phone");
        QuarkusTransaction.requiringNew()
                .run(() -> NotificationChannelMeetingType.replaceLinks(type.id, List.of(a1), List.of(a1)));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("mode", "custom")
                .when()
                .post("/me/meeting-types/" + type.id + "/notifications")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.containsString("at least one"));

        assertEquals(1, router.channelsFor(HOST_A, type.id).size(), "the previous override survives a rejected save");
    }

    @Test
    @TestSecurity(
            user = "cohost",
            roles = {"user"})
    void coHostOverrideLeavesTheCreatorInheriting() {
        MeetingType type = sharedType();
        channel(HOST_A, "a-phone");
        channel(HOST_A, "a-slack");
        var b1 = channel(HOST_B, "b-phone");
        channel(HOST_B, "b-slack");

        given().contentType("application/x-www-form-urlencoded")
                .formParam("mode", "custom")
                .formParam("channelIds", String.valueOf(b1))
                .when()
                .post("/me/shared/" + type.id + "/notifications")
                .then()
                .statusCode(200);

        assertEquals(1, router.channelsFor(HOST_B, type.id).size(), "co-host narrowed");
        assertEquals(2, router.channelsFor(HOST_A, type.id).size(), "creator still inherits");
    }
}
