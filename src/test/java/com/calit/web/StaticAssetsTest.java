package com.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class StaticAssetsTest {

    @Test
    void picoWebjarIsServedVersionAgnostically() {
        given().when().get("/webjars/picocss__pico/css/pico.jade.min.css")
                .then().statusCode(200)
                .contentType(containsString("css"));
    }

    @Test
    void customStylesheetIsServed() {
        given().when().get("/calit.css")
                .then().statusCode(200)
                .body(containsString("--calit"));
    }
}
