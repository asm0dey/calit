package site.asm0dey.calit.user;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class FirstRunLegalPagesTest {

    private void deleteAllUsers() {
        QuarkusTransaction.requiringNew().run(() -> AppUser.deleteAll());
    }

    // Never leave the shared DB at zero users (mirrors SetupFlowTest).
    @AfterEach
    void restoreBaseline() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.count() == 0) {
                AppUser.create("admin", new PasswordHasher().hash("testpass"), true).persist();
            }
        });
    }

    @Test
    void privacyReachableWithNoUsers() {
        deleteAllUsers();
        given().redirects().follow(false)
            .when().get("/privacy").then().statusCode(200)
            .body(containsString("CALIT_LEGAL_PRIVACY"));
    }

    @Test
    void termsReachableWithNoUsers() {
        deleteAllUsers();
        given().redirects().follow(false)
            .when().get("/terms").then().statusCode(200)
            .body(containsString("CALIT_LEGAL_TERMS"));
    }
}
