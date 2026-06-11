package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class MeOwnerFilterTest {

    @Test
    void meDashboardRequiresAuthAndResolvesOwner() {
        // Unauthenticated -> redirected to the form login page (302), never 200.
        given().redirects().follow(false)
            .when().get("/me")
            .then().statusCode(302);

        // Authenticated -> the filter resolves the owner and the dashboard renders.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me")
            .then().statusCode(200);
    }

    @Test
    void oldAdminPathIsGone() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/admin")
            .then().statusCode(404);
    }
}
