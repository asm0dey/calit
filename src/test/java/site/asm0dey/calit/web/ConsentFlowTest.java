package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 19: the public one-click co-host consent flow at {@code /consent/{token}}. No login
 * required — the unguessable {@code consentToken} on the {@link MeetingTypeHost} row is the sole
 * authorization, mirroring the {@code /booking/{manageToken}/...} public trust model.
 */
@QuarkusTest
class ConsentFlowTest {
    @Transactional
    MeetingTypeHost seedPendingHostWithToken(String slug) {
        MeetingType t = MultiHostFixtures.meetingType(1L, slug, 30);
        AppUser candidate = MultiHostFixtures.enabledUser("candidate-" + System.nanoTime());
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost h = MeetingTypeHost.of(t.id, candidate.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        h.consentToken = UUID.randomUUID();
        h.persist();
        return h;
    }

    @Test
    void getConfirmPageShows200AndMarker() {
        MeetingTypeHost h = seedPendingHostWithToken("consent-get-" + System.nanoTime());

        given()
            .when()
            .get("/consent/" + h.consentToken)
            .then()
            .statusCode(200)
            .body(containsString("CALIT_CONSENT_CONFIRM"));
    }

    @Test
    void acceptFlipsRowToAcceptedAndClearsToken() {
        MeetingTypeHost h = seedPendingHostWithToken("consent-accept-" + System.nanoTime());
        String token = h.consentToken.toString();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("action", "accept")
            .when()
            .post("/consent/" + token)
            .then()
            .statusCode(200);

        MeetingTypeHost reloaded = MeetingTypeHost.find(h.meetingTypeId, h.ownerId);
        assertEquals(MeetingTypeHost.ACCEPTED, reloaded.status);
        assertNull(reloaded.consentToken, "token must be cleared after accept");
    }

    @Test
    void declineDeletesTheHostRow() {
        MeetingTypeHost h = seedPendingHostWithToken("consent-decline-" + System.nanoTime());
        String token = h.consentToken.toString();
        Long typeId = h.meetingTypeId;
        Long ownerId = h.ownerId;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("action", "decline")
            .when()
            .post("/consent/" + token)
            .then()
            .statusCode(200);

        assertNull(MeetingTypeHost.find(typeId, ownerId), "declined row must be deleted");
    }

    @Test
    void badTokenReturns404() {
        given().when().get("/consent/" + UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void malformedTokenReturns404NotServerError() {
        given().when().get("/consent/not-a-uuid").then().statusCode(404);
    }

    @Test
    void usedTokenReturns404OnSecondVisit() {
        MeetingTypeHost h = seedPendingHostWithToken("consent-used-" + System.nanoTime());
        String token = h.consentToken.toString();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("action", "accept")
            .when()
            .post("/consent/" + token)
            .then()
            .statusCode(200);
        // Token was cleared by the accept -> the same link is now dead.
        given().when().get("/consent/" + token).then().statusCode(404);
    }
}
