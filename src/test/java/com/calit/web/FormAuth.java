package com.calit.web;

import static io.restassured.RestAssured.given;

/** Test helper: performs a form login and returns the encrypted credential cookie value. */
final class FormAuth {
    private FormAuth() {}

    /** Logs in as the test admin and returns the `quarkus-credential` cookie value. */
    static String login() {
        return given().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "testpass")
                .when().post("/j_security_check")
                .then().extract().cookie("quarkus-credential");
    }
}
