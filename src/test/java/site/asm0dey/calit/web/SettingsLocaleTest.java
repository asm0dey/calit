package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SettingsLocaleTest {

    // Reuse the existing /me login helper used by other admin tests to get an authenticated spec.

    private RequestSpecification authedAdmin() {
        return given().cookie("quarkus-credential", FormAuth.login());
    }

    @Test
    void changingLocaleAppliesInSameResponse() {
        given().spec(authedAdmin())
                .contentType("application/x-www-form-urlencoded")
                .formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "de")
                .formParam("ownerNotificationsEnabled", "on")
                .when().post("/me/settings")
                .then().statusCode(200)
                .body(containsString("value=\"de\" selected"));
    }
}
