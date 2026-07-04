package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AltchaStaticAssetTest {

    // Proves the mvnpm jar is on the classpath AND quarkus-web-dependency-locator
    // serves it at the version-less /_static/... path.
    @Test
    void altchaWidgetScriptIsServedVersionless() {
        given().when()
                .get("/_static/altcha/dist/main/altcha.min.js")
                .then()
                .statusCode(200)
                .body(containsString("altcha"));
    }
}
