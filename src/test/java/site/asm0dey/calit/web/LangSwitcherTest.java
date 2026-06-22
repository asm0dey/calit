package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies that the public footer language-switcher renders both /lang/en and /lang/de links
 * with the correct return path, and marks the active locale.
 */
@QuarkusTest
class LangSwitcherTest {

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
    void publicFooterContainsBothLangLinksWhenGermanActive() {
        seedAlice();
        // With German cookie, EN is the switchable link and DE is the active span
        given()
            .cookie("calit_lang", "de")
            .when().get("/alice/alice-intro")
            .then()
                .statusCode(200)
                .body(containsString("/lang/en?return="))
                .body(containsString("Deutsch"));
    }

    @Test
    void publicFooterContainsBothLangLinksWhenEnglishActive() {
        seedAlice();
        // With English (default), DE is the switchable link and EN is the active span
        given()
            .when().get("/alice/alice-intro")
            .then()
                .statusCode(200)
                .body(containsString("/lang/de?return="))
                .body(containsString("English"));
    }

    @Test
    void returnPathReflectsRequestedPath() {
        seedAlice();
        // The return= value should be the URL-encoded path /alice/alice-intro
        given()
            .when().get("/alice/alice-intro")
            .then()
                .statusCode(200)
                // URL-encoded /alice/alice-intro → %2Falice%2Falice-intro (or the encoded variant)
                .body(containsString("return=%2Falice%2Falice-intro"));
    }

    @Test
    void germanLinkMarkedActiveWithGermanCookie() {
        seedAlice();
        given()
            .cookie("calit_lang", "de")
            .when().get("/alice/alice-intro")
            .then()
                .statusCode(200)
                // The DE link should be the active (non-link) span with aria-current
                .body(containsString("aria-current=\"true\">Deutsch"))
                // The EN link should be a plain link
                .body(containsString("href=\"/lang/en?return="));
    }

    @Test
    void englishLinkMarkedActiveWithDefaultLocale() {
        seedAlice();
        given()
            .when().get("/alice/alice-intro")
            .then()
                .statusCode(200)
                // The EN span should be active
                .body(containsString("aria-current=\"true\">English"))
                // The DE link should be a plain link
                .body(containsString("href=\"/lang/de?return="));
    }

    @Test
    void returnPathEncodedInLangLinks() {
        seedAlice();
        given()
            .cookie("calit_lang", "de")
            .when().get("/alice/alice-intro")
            .then()
                .statusCode(200)
                // With DE cookie, EN is the switchable link; verify return path encoded
                .body(containsString("/lang/en?return=%2Falice%2Falice-intro"));
    }
}
