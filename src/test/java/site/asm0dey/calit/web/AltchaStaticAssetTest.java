package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AltchaStaticAssetTest {

    // Proves the mvnpm jar is on the classpath AND quarkus-web-dependency-locator
    // serves it at the version-less /_static/... path. Guards the i18n build — the exact
    // bundle book.html loads — so a future mvnpm bump that drops/renames it fails here.
    @Test
    void altchaWidgetScriptIsServedVersionless() {
        given().when()
                .get("/webjars/altcha/dist/main/altcha.i18n.min.js")
                .then()
                .statusCode(200)
                .body(containsString("altcha"));
    }
}
