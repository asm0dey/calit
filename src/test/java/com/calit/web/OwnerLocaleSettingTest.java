package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Task 6: owner language setting — persistence + owner-locale override.
 *
 * Test A: saving locale=de persists it; the settings page then shows <option value="de" selected>
 *         and renders "Sprache" (German translation of adm_settings_language) — confirming the
 *         locale was both saved AND drives the rendering of {msg:adm_settings_language}.
 *
 * Test B: owner locale overrides calit_lang cookie. After setting locale=de, the /me/settings
 *         page renders "Sprache" even when the request carries calit_lang=en — proving
 *         LocaleResolutionFilter's owner-scoped path takes precedence over the cookie.
 */
@QuarkusTest
class OwnerLocaleSettingTest {

    /** Test A: save de → GET /me/settings shows de selected + "Sprache" label. */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void savingGermanPersistsLocaleAndRendersGerman() {
        // POST with locale=de (CSRF is OFF in %test — bare form POST is accepted)
        given()
                .formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "de")
                .when().post("/me/settings")
                .then().statusCode(200);

        // GET should show <option value="de" selected> and "Sprache" (adm_settings_language in German)
        given()
                .when().get("/me/settings")
                .then().statusCode(200)
                .body(containsString("value=\"de\" selected"))
                .body(containsString("Sprache"));
    }

    /**
     * Test B: owner locale overrides cookie. Sets owner locale to de via POST, then GETs
     * /me/settings with calit_lang=en cookie — the response must still render in German
     * (i.e. show "Sprache"), proving LocaleResolutionFilter owner-path wins over the cookie.
     */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void ownerLocaleOverridesCookie() {
        // Save owner locale to de
        given()
                .formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "de")
                .when().post("/me/settings")
                .then().statusCode(200);

        // GET with calit_lang=en cookie — owner locale de must still win
        given()
                .cookie("calit_lang", "en")
                .when().get("/me/settings")
                .then().statusCode(200)
                .body(containsString("Sprache")); // German wins over en cookie
    }

    /**
     * Test C: the language select renders iterated locale options, with the saved
     * locale marked selected (Fix 2 — dynamic option iteration).
     */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void settingsPageRendersLocaleOptionsWithDeSelected() {
        // Save locale=de
        given()
                .formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "de")
                .when().post("/me/settings")
                .then().statusCode(200);

        // Settings page must render both locale options and mark de as selected
        given()
                .when().get("/me/settings")
                .then().statusCode(200)
                .body(containsString("value=\"en\""))       // English option present
                .body(containsString("value=\"de\""))       // German option present
                .body(containsString("value=\"de\" selected")); // German is selected
    }
}
