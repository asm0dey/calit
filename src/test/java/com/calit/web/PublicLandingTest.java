package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class PublicLandingTest {

    @Test
    void rootServesGenericCalitIndexNotAnOwnerLanding() {
        given()
            .when().get("/")
            .then()
                .statusCode(200)
                .body(containsString("calit"))
                // Body-specific copy: proves the index body rendered, not just <title>calit</title>.
                .body(containsString("Self-hosted scheduling"))
                .body(containsString("href=\"/login\""))
                .body(not(containsString("Choose a time")));
    }
}
