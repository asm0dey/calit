package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StaticAssetsTest {
    @Test
    void landingPageLinksTheFavicon() {
        given()
            .when()
            .get("/")
            .then()
            .statusCode(200)
            .body(containsString("rel=\"icon\""))
            .body(containsString("/favicon.svg"));
    }
}
