package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleVerificationMetaTest {
    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    @BeforeEach
    void verificationToken() {
        QuarkusMock.installMockForType(
                new SiteInfo(Optional.of("tok_calit_test_123"), Optional.empty(), Optional.empty(), baseUrl),
                SiteInfo.class
        );
    }

    @Test
    void publicPageRendersVerificationMetaWhenConfigured() {
        given()
            .when()
            .get("/login")
            .then()
            .statusCode(200)
            .body(containsString("name=\"google-site-verification\""))
            .body(containsString("tok_calit_test_123"));
    }
}
