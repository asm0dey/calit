package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class SignupResourceTest {
    @Test
    void getReturns404WhenDisabled() {
        given().when().get("/signup").then().statusCode(404);
    }

    @Test
    void postReturns404WhenDisabled() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "frank").formParam("password", "Frank-pw-12345")
            .when().post("/signup").then().statusCode(404);
    }
}
