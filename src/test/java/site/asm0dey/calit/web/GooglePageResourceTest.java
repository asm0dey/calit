package site.asm0dey.calit.web;

import site.asm0dey.calit.google.CalendarListPort;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static io.restassured.RestAssured.given;

@QuarkusTest
class GooglePageResourceTest {

    @io.quarkus.test.InjectMock
    CalendarListPort calendarListPort;

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

    @Test
    void savePersistsSelectedReadAndWriteTarget() {
        long credId = seedSingleAccount();
        Mockito.when(calendarListPort.listCalendars(Mockito.any())).thenReturn(List.of(
                new CalendarListPort.RemoteCalendar("c1", "C1"),
                new CalendarListPort.RemoteCalendar("c2", "C2")));
        given().cookie("quarkus-credential", FormAuth.login())
                .redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("read", credId + ":c1")
                .formParam("read", credId + ":c2")
                .formParam("writeTarget", credId + ":c1")
                .when().post("/me/google/calendars")
                .then().statusCode(303);

        assertWriteTarget(credId, "c1");
        assertReadForBusy(credId, "c1", true);
        assertReadForBusy(credId, "c2", true);
    }

    @Test
    void saveWithoutWriteTargetReturns400() {
        long credId = seedSingleAccount();
        Mockito.when(calendarListPort.listCalendars(Mockito.any())).thenReturn(List.of(
                new CalendarListPort.RemoteCalendar("c1", "C1"),
                new CalendarListPort.RemoteCalendar("c2", "C2")));
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("read", credId + ":c1")
                .formParam("read", credId + ":c2")
                .when().post("/me/google/calendars")
                .then().statusCode(400);
    }

    @Test
    void saveDoesNotWipeFlaggedAccountCalendars() {
        long[] ids = seedHealthyXAndFlaggedY();
        long xId = ids[0];
        long yId = ids[1];
        // Only X is ever listed (Y is needsReconnect, so production code never lists it).
        Mockito.when(calendarListPort.listCalendars(Mockito.any())).thenReturn(List.of(
                new CalendarListPort.RemoteCalendar("x1", "X1")));
        given().cookie("quarkus-credential", FormAuth.login())
                .redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("read", xId + ":x1")
                .formParam("writeTarget", xId + ":x1")
                .when().post("/me/google/calendars")
                .then().statusCode(303);

        // Regression: flagged account Y's saved read calendar must still exist.
        assertCalendarExists(yId, "y1");
        // And X's chosen write target persisted.
        assertWriteTarget(xId, "x1");
    }

    @Transactional
    void assertWriteTarget(long credId, String googleCalId) {
        long n = GoogleCalendar.count(
                "googleCredentialId = ?1 and googleCalendarId = ?2 and writeTarget = true",
                credId, googleCalId);
        org.junit.jupiter.api.Assertions.assertEquals(1, n,
                "expected " + googleCalId + " to be the write target");
    }

    @Transactional
    void assertReadForBusy(long credId, String googleCalId, boolean expected) {
        long n = GoogleCalendar.count(
                "googleCredentialId = ?1 and googleCalendarId = ?2 and readForBusy = ?3",
                credId, googleCalId, expected);
        org.junit.jupiter.api.Assertions.assertEquals(1, n,
                "expected readForBusy=" + expected + " for " + googleCalId);
    }

    @Transactional
    void assertCalendarExists(long credId, String googleCalId) {
        long n = GoogleCalendar.count(
                "googleCredentialId = ?1 and googleCalendarId = ?2", credId, googleCalId);
        org.junit.jupiter.api.Assertions.assertEquals(1, n,
                "expected calendar " + googleCalId + " to still exist for credential " + credId);
    }

    @Transactional
    long[] seedHealthyXAndFlaggedY() {
        long ownerId = site.asm0dey.calit.user.AppUser.<site.asm0dey.calit.user.AppUser>find("username", "admin").firstResult().id;
        GoogleCredential x = cred(ownerId, "sub-X"); x.persist();
        GoogleCredential y = cred(ownerId, "sub-Y"); y.needsReconnect = true; y.persist();
        // X currently holds the write target.
        GoogleCalendar xw = new GoogleCalendar();
        xw.ownerId = ownerId; xw.googleCredentialId = x.id; xw.googleCalendarId = "x1";
        xw.summary = "X1"; xw.readForBusy = true; xw.writeTarget = true; xw.persist();
        // Y (flagged) has a saved read calendar that must survive a save.
        GoogleCalendar yr = new GoogleCalendar();
        yr.ownerId = ownerId; yr.googleCredentialId = y.id; yr.googleCalendarId = "y1";
        yr.summary = "Y1"; yr.readForBusy = true; yr.writeTarget = false; yr.persist();
        return new long[]{x.id, y.id};
    }

    @Transactional
    long seedTwoAccountsWriteOnFirst() {
        long ownerId = site.asm0dey.calit.user.AppUser.<site.asm0dey.calit.user.AppUser>find("username", "admin").firstResult().id;
        GoogleCredential a = cred(ownerId, "sub-A"); a.persist();
        GoogleCredential b = cred(ownerId, "sub-B"); b.persist();
        GoogleCalendar w = new GoogleCalendar();
        w.ownerId = ownerId; w.googleCredentialId = a.id; w.googleCalendarId = "w";
        w.summary = "W"; w.readForBusy = true; w.writeTarget = true; w.persist();
        return a.id;
    }

    @Transactional
    long seedSingleAccount() {
        long ownerId = site.asm0dey.calit.user.AppUser.<site.asm0dey.calit.user.AppUser>find("username", "admin").firstResult().id;
        GoogleCredential a = cred(ownerId, "sub-A"); a.persist();
        return a.id;
    }

    private static GoogleCredential cred(long owner, String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner; c.refreshToken = "rt"; c.googleSub = sub; c.accountEmail = sub + "@x";
        return c;
    }
}
