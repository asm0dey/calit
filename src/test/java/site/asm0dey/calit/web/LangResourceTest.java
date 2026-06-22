package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class LangResourceTest {
    @Test void setsCookieAndRedirectsToReturn() {
        given().redirects().follow(false)
            .when().get("/lang/de?return=/alice/intro")
            .then().statusCode(303)
            .header("Location", endsWith("/alice/intro"))
            .cookie("calit_lang", "de");
    }
    @Test void unsupportedCodeIgnoredCookieNotSet() {
        given().redirects().follow(false)
            .when().get("/lang/fr?return=/x")
            .then().statusCode(303).header("Set-Cookie", anyOf(nullValue(), not(containsString("calit_lang=fr"))));
    }
    @Test void rejectsNonLocalReturn() {
        given().redirects().follow(false)
            .when().get("/lang/de?return=https://evil.test/x")
            .then().statusCode(303).header("Location", endsWith("/"));
    }
    @Test void rejectsProtocolRelativeReturn() {
        given().redirects().follow(false)
            .when().get("/lang/de?return=//evil.com")
            .then().statusCode(303)
            .header("Location", endsWith("/"))
            .cookie("calit_lang", "de");
    }
    @Test void rejectsMalformedBackslashReturn() {
        given().redirects().follow(false)
            .when().get("/lang/de?return=/\\evil.com")
            .then().statusCode(303)
            .header("Location", endsWith("/"));
    }
}
