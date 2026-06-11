package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminGoogleTest {

    @Test
    void googlePageLinksToConnectEndpoint() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/google")
            .then()
                .statusCode(200)
                .body(containsString("/api/google/connect"))
                .body(containsString("Connect Google"));
    }

    @Test
    void googlePageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/google").then().statusCode(302);
    }
}
