package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LegalPagesTest {
    @Test
    void privacyPageRendersWithGoogleDisclosure() {
        given()
            .when()
            .get("/privacy")
            .then()
            .statusCode(200)
            .body(containsString("CALIT_LEGAL_PRIVACY"))
            .body(containsString("Google Calendar"))
            .body(containsString("Limited Use"));
    }

    @Test
    void termsPageRenders() {
        given().when().get("/terms").then().statusCode(200).body(containsString("CALIT_LEGAL_TERMS"));
    }
}
