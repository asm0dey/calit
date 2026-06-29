package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AdminGoogleTest {

    @Test
    void googlePageLinksToConnectEndpoint() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/google")
                .then()
                .statusCode(200)
                .body(containsString("/api/google/connect"))
                .body(containsString("Connect a Google account"));
    }

    @Test
    void googlePageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/google").then().statusCode(302);
    }
}
