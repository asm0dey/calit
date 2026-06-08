package com.calit.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class BookingFieldResourceTest {

    @Test
    void createFieldForTypeAndReadResolvedForm() {
        // Create a meeting type to attach fields to.
        String slug = "fields-intro-" + System.nanoTime();
        Integer typeId = given().contentType("application/json")
                .body("{\"name\":\"Fields Intro\",\"slug\":\"" + slug + "\",\"durationMinutes\":30}")
                .when().post("/api/meeting-types")
                .then().statusCode(201)
                .extract().path("id");

        // Add a required custom field for that type.
        given().contentType("application/json")
                .body("{\"meetingTypeId\":" + typeId + ",\"fieldKey\":\"company\",\"label\":\"Company\","
                        + "\"type\":\"SHORT_TEXT\",\"required\":true,\"position\":0}")
                .when().post("/api/booking-fields")
                .then().statusCode(201);

        // Resolved form for that type's slug exposes the custom field.
        given().when().get("/api/meeting-types/" + slug + "/form")
                .then().statusCode(200).body("fieldKey", hasItem("company"));
    }
}
