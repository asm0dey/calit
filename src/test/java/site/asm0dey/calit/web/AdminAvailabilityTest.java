package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminAvailabilityTest {

    @Test
    void createGlobalRuleViaForm() {
        long before = AvailabilityRule.count();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("dayOfWeek", "TUESDAY")
            .formParam("startTime", "10:00")
            .formParam("endTime", "16:00")
            .formParam("meetingTypeId", "") // empty = global
            .when().post("/me/availability")
            .then()
                .statusCode(200)
                .body(containsString("TUESDAY"));
    }

    @Test
    void availabilityPageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/availability").then().statusCode(302);
    }
}
