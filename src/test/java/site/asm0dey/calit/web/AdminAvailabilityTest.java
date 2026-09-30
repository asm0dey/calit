package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

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
            // empty = global
            .formParam(
                    // empty = global
            "meetingTypeId",
                    "")
            .when()
            .post("/me/availability")
            .then()
            .statusCode(200)
            .body(containsString("TUESDAY"));
    }

    @Test
    void createRuleSkipsInvertedTimesAndUnknownDay() {
        long before = AvailabilityRule.count();
        String[][] bad =
                {{"TUESDAY", "16:00", "10:00"}, {"TUESDAY", "10:00", "10:00"}, {"FUNDAY", "10:00", "16:00"}};
        for (String[] c : bad) {
            given()
                .cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", c[0])
                .formParam("startTime", c[1])
                .formParam("endTime", c[2])
                .formParam("meetingTypeId", "")
                .when()
                .post("/me/availability")
                .then()
                .statusCode(200);
        }
        assertEquals(before, AvailabilityRule.count(), "no inverted, empty or unknown-day rule may be stored");
    }

    @Test
    void availabilityPageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/availability").then().statusCode(302);
    }
}
