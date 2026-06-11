package com.calit.web;

import com.calit.user.AppUser;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignupEnabledProfile.class)
class SignupEnabledTest {

    @Test
    void getRendersForm() {
        given().when().get("/signup").then().statusCode(200).body(containsString("Sign up"));
    }

    @Test
    void postCreatesSelfServiceUser() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "grace").formParam("password", "Grace-pw-12345")
            .redirects().follow(false)
            .when().post("/signup").then().statusCode(303); // -> /login
        AppUser grace = AppUser.findByUsername("grace");
        assertNotNull(grace);
        assertFalse(grace.mustChangePassword); // self-chosen password → no forced reset
        assertFalse(grace.settingsComplete);   // still must do the settings wizard
        assertTrue(grace.enabled);
        assertFalse(grace.isAdmin);
        assertEquals("user", grace.roles);
    }

    @Test
    void postRejectsReservedUsername() {
        // Reserved word -> re-render form (200) with the error, no user created.
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "api").formParam("password", "pw-1234567")
            .when().post("/signup").then().statusCode(200).body(containsString("reserved"));
        assertNull(AppUser.findByUsername("api"));
    }
}
