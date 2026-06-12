package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class StaticAssetsTest {

    @Test
    void customStylesheetIsServed() {
        given().when().get("/calit.css")
                .then().statusCode(200)
                .contentType(containsString("css"))
                .body(containsString("--color-base-100"));
    }

    @Test
    void faviconIsServedAsSvg() {
        given().when().get("/favicon.svg")
                .then().statusCode(200)
                .contentType(containsString("svg"))
                .body(containsString("<svg"))
                .body(containsString("#6061f8")); // brand indigo, matching the landing "c" chip
    }

    @Test
    void landingPageLinksTheFavicon() {
        given().when().get("/")
                .then().statusCode(200)
                .body(containsString("rel=\"icon\""))
                .body(containsString("/favicon.svg"));
    }
}
