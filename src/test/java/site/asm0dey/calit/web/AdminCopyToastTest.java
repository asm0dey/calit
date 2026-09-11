package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// #195: copying a booking link is the moment before the owner hands it to someone. If mail is
// broken, the toast must say so instead of a cheerful green "Copied". %test runs the mock mailer,
// so MailHealth is UNCONFIGURED and the degraded variant is what these pages should render.
@QuarkusTest
class AdminCopyToastTest {

    @Test
    void meetingTypesPageCarriesTheDegradedToastCopy() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-degraded=\"true\""))
                .body(containsString("will get no confirmation"));
    }

    @Test
    void sharedPageCarriesTheDegradedToastCopy() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/shared")
                .then()
                .statusCode(200)
                .body(containsString("data-mail-degraded=\"true\""));
    }
}
