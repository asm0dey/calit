package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FooterPolishTest {

    // The shared footer must appear on a PUBLIC page with build info, legal links,
    // and the no-JS language dropdown (a /lang link + the active endonym).
    @Test
    void publicFooterHasSharedContent() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_BUILD_FOOTER"))
                .body(containsString("href=\"/privacy\""))
                .body(containsString("href=\"/terms\""))
                .body(containsString("class=\"dropdown"))
                .body(containsString("/lang/"))
                .body(containsString("English")); // active endonym in default (en) test locale
    }

    // The SAME footer must now also appear on an ADMIN page (was missing before).
    @Test
    void adminFooterHasSharedContent() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_BUILD_FOOTER"))
                .body(containsString("href=\"/privacy\""))
                .body(containsString("href=\"/terms\""))
                .body(containsString("class=\"dropdown"))
                .body(containsString("/lang/"));
    }
}
