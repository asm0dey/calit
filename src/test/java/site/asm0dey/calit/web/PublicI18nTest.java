package site.asm0dey.calit.web;

import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies that the public booking flow renders in the correct language based on
 * the calit_lang cookie, and that the base layout emits the matching lang attribute.
 */
@QuarkusTest
class PublicI18nTest {

    /** Seeds alice with one public meeting type — idempotent, safe to call before each test. */
    @Transactional
    void seedAlice() {
        AppUser alice = AppUser.findByUsername("alice");
        if (alice == null) {
            alice = AppUser.create("alice", "x", false);
            alice.persist();
        }
        Long ownerId = alice.id;

        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "alice-intro");

        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Alice Owner"; s.ownerEmail = "alice@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType pub = new MeetingType();
        pub.ownerId = ownerId; pub.name = "Alice Intro Call"; pub.slug = "alice-intro";
        pub.durationMinutes = 30;
        pub.persist();
    }

    @Test
    void publicUserLandingRendersGermanViaCookie() {
        seedAlice();
        given()
            .cookie("calit_lang", "de")
            .when().get("/alice")
            .then()
                .statusCode(200)
                .body(containsString("<html lang=\"de\""))
                .body(containsString("Termin buchen"));
    }

    @Test
    void publicUserLandingRendersEnglishByDefault() {
        seedAlice();
        given()
            .when().get("/alice")
            .then()
                .statusCode(200)
                .body(containsString("<html lang=\"en\""))
                .body(containsString("Book a meeting"));
    }

    @Test
    void germanUserLandingHasGermanTitle() {
        seedAlice();
        given()
            .cookie("calit_lang", "de")
            .when().get("/alice")
            .then()
                .statusCode(200)
                .body(containsString("<title>Termin buchen</title>"));
    }

    @Test
    void englishUserLandingHasEnglishTitle() {
        seedAlice();
        given()
            .when().get("/alice")
            .then()
                .statusCode(200)
                .body(containsString("<title>Book a meeting</title>"));
    }
}
