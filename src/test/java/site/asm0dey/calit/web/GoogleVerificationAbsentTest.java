package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class GoogleVerificationAbsentTest {

    @Test
    void noVerificationMetaWhenUnset() {
        given()
            .when().get("/login")
            .then()
            .statusCode(200)
            .body(not(containsString("google-site-verification")));
    }
}
