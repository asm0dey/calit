package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * /calit is the product page's permanent home and the escape hatch for a signed-in user whose /
 * goes to their dashboard. It NEVER redirects, whoever asks for it.
 */
@QuarkusTest
class ProductPageTest {
    @Test
    void productPageServesTheMarketingContentToAnonymousVisitors() {
        given()
            .redirects()
            .follow(false)
            .when()
            .get("/calit")
            .then()
            .statusCode(200)
            .body(containsString("Self-hosted scheduling"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void productPageNeverRedirectsASignedInVisitor() {
        given()
            .redirects()
            .follow(false)
            .when()
            .get("/calit")
            .then()
            .statusCode(200)
            .body(containsString("Self-hosted scheduling"));
    }

    @Test
    void authVaryingHtmlIsNeverStoredByASharedCache() {
        given().when().get("/calit").then().header("Cache-Control", equalTo("private"));
        given().when().get("/").then().header("Cache-Control", equalTo("private"));
    }

    @Test
    void productPageDeclaresTheHomePageAsCanonical() {
        given().when().get("/calit").then().statusCode(200).body(containsString("rel=\"canonical\" href=\"/\""));
    }

    /**
     * /calit's own "calit" brand anchors are its only "go home" control. For a signed-in visitor
     * they must point back at /calit itself, not at / -- since / 303s a signed-in visitor straight
     * back to /me, a brand anchor pointing at / would bounce them off the page they just landed on.
     * Anonymous visitors keep the canonical / so the shareable URL stays the bare domain.
     */
    @Test
    @TestSecurity(user = "admin", roles = "user")
    void signedInVisitorsBrandAnchorsPointBackAtProductPage() {
        given()
            .when()
            .get("/calit")
            .then()
            .statusCode(200)
            .body(containsString("class=\\\"lp-brand\\\" " + "href=\\\"/calit\\\""));
    }

    @Test
    void anonymousVisitorsBrandAnchorsPointAtHome() {
        given().when().get("/calit").then().statusCode(200).body(containsString("class=\"lp-brand\" href=\"/\""));
    }
}
