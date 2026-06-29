package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LegalPagesTest {

    @Test
    void privacyPageRendersWithGoogleDisclosure() {
        given().when()
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

    @Test
    void publicFooterLinksToLegalPages() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("href=\"/privacy\""))
                .body(containsString("href=\"/terms\""));
    }

    @org.junit.jupiter.api.Test
    void privacyHasCanonicalSectionsAndLandingStyle() {
        io.restassured.RestAssured.given()
                .when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Retention and deletion"))
                .body(org.hamcrest.Matchers.containsString("How Google user data is used"))
                .body(org.hamcrest.Matchers.containsString("max-w-3xl"))
                .body(org.hamcrest.Matchers.containsString("text-3xl font-bold"))
                .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("class=\"prose")));
    }

    @org.junit.jupiter.api.Test
    void termsUsesLandingStyle() {
        io.restassured.RestAssured.given()
                .when()
                .get("/terms")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.containsString("max-w-3xl"))
                .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("class=\"prose")));
    }
}
