package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@QuarkusTest
class GoogleOAuthResourceTest {

    @InjectMock
    GoogleTokenService tokenService;

    @Test
    void connectRedirectsToGoogleConsent() {
        Mockito.when(tokenService.buildConsentUrl())
                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?access_type=offline&prompt=consent");

        RestAssured.given().redirects().follow(false)
                .cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .when().get("/api/google/connect")
                .then().statusCode(302)
                .header("Location", containsString("accounts.google.com"))
                .header("Location", containsString("access_type=offline"));
    }

    @Test
    void callbackExchangesCodeAndRedirectsToAdmin() {
        // Stateless CSRF: the mocked service accepts this state without any session.
        Mockito.when(tokenService.validateState(eq("good-state"), any(Instant.class))).thenReturn(true);
        doNothing().when(tokenService).exchangeCode(any(), eq("the-code"), any(Instant.class));

        RestAssured.given().redirects().follow(false)
                .cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .when().get("/api/google/callback?code=the-code&state=good-state")
                .then().statusCode(302)
                .header("Location", containsString("/me"));

        verify(tokenService).exchangeCode(any(), eq("the-code"), any(Instant.class));
    }

    @Test
    void callbackWithInvalidStateReturns400() {
        // Forged/expired state is rejected before any code exchange — no session to consult.
        Mockito.when(tokenService.validateState(eq("bad-state"), any(Instant.class))).thenReturn(false);

        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .when().get("/api/google/callback?code=the-code&state=bad-state")
                .then().statusCode(400).body(containsString("Invalid or expired OAuth state"));

        verify(tokenService, Mockito.never()).exchangeCode(any(), any(), any());
    }

    @Test
    void callbackWithErrorReturns400() {
        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .when().get("/api/google/callback?error=access_denied")
                .then().statusCode(400).body(containsString("access_denied"));
    }
}
