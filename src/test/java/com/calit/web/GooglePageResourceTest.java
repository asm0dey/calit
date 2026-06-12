package com.calit.web;

import com.calit.google.GoogleCalendar;
import com.calit.google.GoogleCredential;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class GooglePageResourceTest {

    @Test
    void disconnectBlockedWhenItHoldsWriteTargetAndOtherAccountsRemain() {
        long credId = seedTwoAccountsWriteOnFirst();
        given().cookie("quarkus-credential", FormAuth.login())
                .when().post("/me/google/accounts/" + credId + "/delete")
                .then().statusCode(409);
    }

    @Test
    void disconnectAllowedForLastAccount() {
        long credId = seedSingleAccount();
        given().cookie("quarkus-credential", FormAuth.login())
                .redirects().follow(false)
                .when().post("/me/google/accounts/" + credId + "/delete")
                .then().statusCode(303);
    }

    @Test
    void getRendersConnectButtonWhenNoAccounts() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when().get("/me/google")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("Connect a Google account"));
    }

    @Transactional
    long seedTwoAccountsWriteOnFirst() {
        long ownerId = com.calit.user.AppUser.<com.calit.user.AppUser>find("username", "admin").firstResult().id;
        GoogleCredential a = cred(ownerId, "sub-A"); a.persist();
        GoogleCredential b = cred(ownerId, "sub-B"); b.persist();
        GoogleCalendar w = new GoogleCalendar();
        w.ownerId = ownerId; w.googleCredentialId = a.id; w.googleCalendarId = "w";
        w.summary = "W"; w.readForBusy = true; w.writeTarget = true; w.persist();
        return a.id;
    }

    @Transactional
    long seedSingleAccount() {
        long ownerId = com.calit.user.AppUser.<com.calit.user.AppUser>find("username", "admin").firstResult().id;
        GoogleCredential a = cred(ownerId, "sub-A"); a.persist();
        return a.id;
    }

    private static GoogleCredential cred(long owner, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner; c.refreshToken = "rt"; c.googleSub = sub; c.accountEmail = sub + "@x";
        return c;
    }
}
