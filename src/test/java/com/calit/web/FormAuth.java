package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.PasswordHasher;
import io.quarkus.narayana.jta.QuarkusTransaction;

import static io.restassured.RestAssured.given;

/** Test helper: seeds a DB admin user, performs a form login, returns the credential cookie. */
final class FormAuth {
    private FormAuth() {}

    private static final PasswordHasher HASHER = new PasswordHasher();

    /** Idempotently ensure an enabled admin user 'admin'/'testpass' exists. Own transaction. */
    static void ensureAdminSeeded() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!AppUser.usernameTaken("admin")) {
                AppUser u = AppUser.create("admin", HASHER.hash("testpass"), true);
                u.persist();
            }
        });
    }

    /** Logs in as the seeded test admin and returns the `quarkus-credential` cookie value. */
    static String login() {
        ensureAdminSeeded();
        return given().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "admin")
                .formParam("j_password", "testpass")
                .when().post("/j_security_check")
                .then().extract().cookie("quarkus-credential");
    }
}
