package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class PublicUserRoutingTest {

    /** Idempotent across committed tx: a user "alice" with settings + one public + one secret type. */
    @Transactional
    Long seedAlice() {
        AppUser alice = AppUser.findByUsername("alice");
        if (alice == null) {
            // create(...) stamps roles + created_at (both NOT NULL); password not exercised here.
            alice = AppUser.create("alice", "x", false);
            alice.persist();
        }
        Long ownerId = alice.id;

        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "alice-intro");
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "alice-secret");

        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = "Alice Owner";
        s.ownerEmail = "alice@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType pub = new MeetingType();
        pub.ownerId = ownerId;
        pub.name = "Alice Intro Call";
        pub.slug = "alice-intro";
        pub.durationMinutes = 30;
        pub.persist();

        MeetingType secret = new MeetingType();
        secret.ownerId = ownerId;
        secret.name = "Alice Secret Session";
        secret.slug = "alice-secret";
        secret.durationMinutes = 30;
        secret.secret = true;
        secret.persist();
        return ownerId;
    }

    @Test
    void userLandingListsThatOwnersPublicTypesAndHidesSecret() {
        seedAlice();
        given().when()
                .get("/alice")
                .then()
                .statusCode(200)
                .body(containsString("Alice Owner"))
                .body(containsString("Alice Intro Call"))
                .body(containsString("href=\"/alice/alice-intro\""))
                .body(not(containsString("Alice Secret Session")));
    }

    @Test
    void unknownUserLandingReturns404() {
        given().when().get("/nobody-here").then().statusCode(404);
    }
}
