package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LandingFooterTest {

    @Test
    void landingPinsLightThemeAndOwnsItsFooter() {
        given().when()
                .get("/")
                .then()
                .statusCode(200)
                // landing body is pinned to the light theme so its daisyUI bits match its cream palette
                .body(containsString("data-theme=\"calit-light\""))
                // legal links live in the landing's own footer
                .body(containsString("href=\"/privacy\""))
                .body(containsString("href=\"/terms\""))
                // the shared daisyUI footer (with the language dropdown) is NOT appended on the landing
                .body(not(containsString("class=\"dropdown")));
    }
}
