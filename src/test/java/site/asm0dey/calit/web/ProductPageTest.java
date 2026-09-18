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
        given().redirects()
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
        given().redirects()
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
}
