package com.calit.web;

import com.calit.domain.AvailabilityRule;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminAvailabilityTest {

    @Test
    void createGlobalRuleViaForm() {
        long before = AvailabilityRule.count();
        given()
            .auth().preemptive().basic("admin", "testpass")
            .contentType("application/x-www-form-urlencoded")
            .formParam("dayOfWeek", "TUESDAY")
            .formParam("startTime", "10:00")
            .formParam("endTime", "16:00")
            .formParam("meetingTypeId", "") // empty = global
            .when().post("/admin/availability")
            .then()
                .statusCode(200)
                .body(containsString("TUESDAY"));
    }

    @Test
    void availabilityPageRequiresAuth() {
        given().when().get("/admin/availability").then().statusCode(401);
    }
}
