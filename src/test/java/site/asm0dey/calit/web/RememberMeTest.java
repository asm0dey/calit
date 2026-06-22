package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RememberMeTest {

    private Cookie loginCookie(boolean remember) {
        var req = given().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "testpass");
        if (remember) { req = req.queryParam("remember", "true"); }
        return req.when().post("/j_security_check")
                .then().extract().detailedCookie("quarkus-credential");
    }

    @Test
    void rememberMeMakesCredentialCookiePersistent() {
        Cookie c = loginCookie(true);
        assertNotNull(c);
        assertTrue(c.getMaxAge() > 0, "Expected positive Max-Age for persistent cookie, got: " + c.getMaxAge());
    }

    @Test
    void withoutRememberCredentialCookieIsSessionScoped() {
        Cookie c = loginCookie(false);
        assertNotNull(c);
        assertEquals(-1L, c.getMaxAge(), "Expected -1 Max-Age for session cookie");
    }
}
