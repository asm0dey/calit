package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
@TestProfile(CommonFeaturesProfile.class)
class SignupEnabledTest {

    @Test
    void getRendersForm() {
        given().when().get("/signup").then().statusCode(200).body(containsString("Sign up"));
    }

    @Test
    void postCreatesSelfServiceUser() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "grace")
                .formParam("password", "Grace-pw-12345")
                .redirects()
                .follow(false)
                .when()
                .post("/signup")
                .then()
                .statusCode(303); // -> /login
        AppUser grace = AppUser.findByUsername("grace");
        assertNotNull(grace);
        assertFalse(grace.mustChangePassword); // self-chosen password → no forced reset
        assertFalse(grace.settingsComplete); // still must do the settings wizard
        assertTrue(grace.enabled);
        assertFalse(grace.isAdmin);
        assertEquals("user", grace.roles);
    }

    @Test
    void postSeedsTheOwnerSettingsRow() {
        // owner_name / owner_email / timezone are NOT NULL and the public booking path reads
        // OwnerSettings.forOwner(id).timezone unguarded (issue #99). Every other creation path
        // seeds the row; /signup must too (calit-a4yj).
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "heidi")
                .formParam("password", "Heidi-pw-12345")
                .redirects()
                .follow(false)
                .when()
                .post("/signup")
                .then()
                .statusCode(303);

        AppUser heidi = AppUser.findByUsername("heidi");
        var settings = site.asm0dey.calit.domain.OwnerSettings.forOwner(heidi.id);
        assertNotNull(settings, "a /signup user must have an owner_settings row immediately");
        assertEquals("UTC", settings.timezone);
        assertEquals("", settings.ownerName);
        assertEquals("", settings.ownerEmail);
    }

    @Test
    void postRejectsReservedUsername() {
        // Reserved word -> re-render form (200) with the localized aggregate error, no user created.
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "api")
                .formParam("password", "pw-1234567")
                .when()
                .post("/signup")
                .then()
                .statusCode(200)
                .body(containsString("invalid, reserved, or already taken"));
        assertNull(AppUser.findByUsername("api"));
    }

    @Test
    void postRejectsReservedUsernameGerman() {
        // German locale via cookie — error must be in German, not the raw English exception text.
        given().contentType("application/x-www-form-urlencoded")
                .cookie("calit_lang", "de")
                .formParam("username", "api")
                .formParam("password", "pw-1234567")
                .when()
                .post("/signup")
                .then()
                .statusCode(200)
                .body(containsString("Dieser Benutzername kann nicht verwendet werden"));
        assertNull(AppUser.findByUsername("api"));
    }
}
