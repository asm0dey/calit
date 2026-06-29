package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FooterBuildInfoTest {

    @Test
    void publicPageHasBuildFooter() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_BUILD_FOOTER"))
                .body(containsString("calit"));
    }
}
