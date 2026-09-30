package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class AdminLockoutGuardTest {
    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void soleAdminCannotRevokeOwnAdmin() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/users/1/revoke-admin")
            .then()
            .statusCode(200)
            .body(containsString("last enabled admin"));
        assertStillAdminAndEnabled();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void soleAdminCannotLockSelf() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/users/1/lock")
            .then()
            .statusCode(200)
            .body(containsString("cannot lock your own"));
        assertStillAdminAndEnabled();
    }

    @Transactional
    void assertStillAdminAndEnabled() {
        AppUser admin = AppUser.findById(1L);
        assertThat(admin.isAdmin).as("admin role must be intact").isTrue();
        assertThat(admin.enabled).as("account must stay enabled").isTrue();
    }
}
