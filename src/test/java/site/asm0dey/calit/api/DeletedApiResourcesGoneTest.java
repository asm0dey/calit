package site.asm0dey.calit.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/** The unauthenticated JSON CRUD resources are removed; their paths must 404. /api/google stays. */
@QuarkusTest
class DeletedApiResourcesGoneTest {

    @Test
    void meetingTypesCrudGone() {
        given().when().get("/api/meeting-types").then().statusCode(404);
    }

    @Test
    void settingsCrudGone() {
        given().contentType("application/json").body("{}")
            .when().put("/api/settings").then().statusCode(404);
    }

    @Test
    void availabilityCrudGone() {
        given().contentType("application/json").body("{}")
            .when().post("/api/availability").then().statusCode(404);
    }

    @Test
    void bookingFieldsCrudGone() {
        given().contentType("application/json").body("{}")
            .when().post("/api/booking-fields").then().statusCode(404);
    }
}
