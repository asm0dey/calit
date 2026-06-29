package site.asm0dey.calit.user;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LoginSpikeTest {

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!AppUser.usernameTaken("spikeuser")) {
                AppUser u = AppUser.create("spikeuser", new PasswordHasher().hash("spikepass"), true);
                u.persist();
            }
        });
    }

    @Test
    void formLoginIssuesCredentialCookieForDbUser() {
        given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "spikeuser")
                .formParam("j_password", "spikepass")
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(302)
                .cookie("quarkus-credential", notNullValue());
    }

    @Test
    void wrongPasswordIsRejected() {
        given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "spikeuser")
                .formParam("j_password", "WRONG")
                .when()
                .post("/j_security_check")
                .then()
                .statusCode(302)
                .header("Location", org.hamcrest.Matchers.containsString("error=true"));
    }
}
