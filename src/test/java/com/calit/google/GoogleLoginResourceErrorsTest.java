package com.calit.google;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/** Error/edge paths of the Google sign-in callback (default profile: signup disabled). */
@QuarkusTest
class GoogleLoginResourceErrorsTest {

    @Inject
    GoogleOAuthConfig oauthConfig;

    @Inject
    GoogleLoginService loginService;

    private static final java.time.Instant FIXED = java.time.Instant.parse("2026-06-12T12:00:00Z");

    @BeforeEach
    void freezeClock() {
        io.quarkus.test.junit.QuarkusMock.installMockForType(
            java.time.Clock.fixed(FIXED, java.time.ZoneOffset.UTC), java.time.Clock.class);
    }

    static class FixedIdentityStub extends GoogleLoginService {
        FixedIdentityStub(GoogleOAuthConfig c) { super(c); }
        @Override public GoogleIdentity exchangeForIdentity(String code, Instant now) {
            return new GoogleIdentity("sub-unknown-err", "nobody-new@example.com", true);
        }
    }

    static class ThrowingStub extends GoogleLoginService {
        ThrowingStub(GoogleOAuthConfig c) { super(c); }
        @Override public GoogleIdentity exchangeForIdentity(String code, Instant now) {
            throw new IllegalStateException("simulated Google token failure");
        }
    }

    @Test
    void forgedStateRedirectsToLoginWithNotice() {
        // No stub needed — state validation fails before any exchange.
        given().redirects().follow(false)
            .when().get("/api/google/login/callback?code=abc&state=forged.value")
            .then().statusCode(302)
            .header("Location", containsString("/login?notice=google"));
    }

    @Test
    void signupDisabledRedirectsWithSpecificNotice() {
        QuarkusMock.installMockForType(new FixedIdentityStub(oauthConfig), GoogleLoginService.class);
        String state = loginService.issueLoginState(FIXED);
        given().redirects().follow(false)
            .when().get("/api/google/login/callback?code=abc&state=" + state)
            .then().statusCode(302)
            .header("Location", containsString("notice=google_signup_disabled"));
    }

    @Test
    void tokenExchangeFailureRedirectsToLogin() {
        QuarkusMock.installMockForType(new ThrowingStub(oauthConfig), GoogleLoginService.class);
        String state = loginService.issueLoginState(FIXED);
        given().redirects().follow(false)
            .when().get("/api/google/login/callback?code=abc&state=" + state)
            .then().statusCode(302)
            .header("Location", containsString("/login?notice=google"));
    }
}
