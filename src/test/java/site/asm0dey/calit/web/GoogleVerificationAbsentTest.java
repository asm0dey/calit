package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleVerificationAbsentTest {

    @Test
    void noVerificationMetaWhenUnset() {
        given().when().get("/login").then().statusCode(200).body(not(containsString("google-site-verification")));
    }
}
