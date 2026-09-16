package site.asm0dey.calit.privacy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BookingExportTest {

    @Test
    void exportCarriesTheInviteesOwnData() {
        String token = ErasureFixtures.seedPastBooking();
        given().when()
                .get("/booking/" + token + "/data")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .header("Content-Disposition", containsString("attachment"))
                .header("Cache-Control", containsString("no-store"))
                .body("invitee.name", equalTo("Dana Vogel"))
                .body("invitee.email", equalTo("dana@example.com"))
                .body("invitee.answers.why", equalTo("annual review"))
                .body("guests", hasSize(1))
                .body("guests[0].email", equalTo("guest@example.com"))
                .body("booking.startUtc", notNullValue())
                .body("exportedAt", notNullValue());
    }

    @Test
    void erasedBookingHasNothingToExport() {
        String token = ErasureFixtures.seedPastBooking();
        given().when().post("/booking/" + token + "/erase").then().statusCode(200);
        given().when().get("/booking/" + token + "/data").then().statusCode(404);
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/does-not-exist/data").then().statusCode(404);
    }
}
