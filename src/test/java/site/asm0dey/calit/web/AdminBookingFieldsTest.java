package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.BookingField.FieldType;

@QuarkusTest
class AdminBookingFieldsTest {

    /**
     * These tests COMMIT BookingField rows (form POSTs run in their own committed
     * transaction). A leaked GLOBAL required field would make every later test that
     * books a meeting type without per-type fields fail validateRequiredFields.
     * So delete everything this class created after each test.
     */
    @AfterEach
    @Transactional
    void cleanUp() {
        BookingField.delete("fieldKey = ?1 or fieldKey like ?2", "linkedin", "field-%");
    }

    @Transactional
    void seedField() {
        BookingField f = new BookingField();
        f.ownerId = 1L;
        f.meetingTypeId = null;
        f.fieldKey = "linkedin";
        f.label = "LinkedIn URL";
        f.type = FieldType.SHORT_TEXT;
        f.required = false;
        f.position = 5;
        f.persist();
    }

    @Test
    void pageRendersExistingFieldsAndCreateForm() {
        seedField();
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/booking-fields")
                .then()
                .statusCode(200)
                .body(containsString("LinkedIn URL")) // existing field listed
                .body(containsString("name=\"fieldKey\"")) // create form present
                .body(containsString("name=\"type\"")) // type dropdown
                .body(containsString("name=\"required\"")); // required checkbox
    }

    @Test
    void createFieldViaForm() {
        var key = "field-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("label", "Dietary Needs")
                .formParam("fieldKey", key)
                .formParam("type", "LONG_TEXT")
                .formParam("required", "on")
                .formParam("position", "10")
                .formParam("meetingTypeId", "") // empty = global
                .when()
                .post("/me/booking-fields")
                .then()
                .statusCode(200)
                .body(containsString("Dietary Needs"))
                .body(containsString(key));
    }

    @Test
    void bookingFieldsPageRequiresAuth() {
        given().redirects()
                .follow(false)
                .when()
                .get("/me/booking-fields")
                .then()
                .statusCode(302);
    }
}
