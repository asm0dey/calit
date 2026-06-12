package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class GoogleCalendarResourceTest {

    @InjectMock
    CalendarListPort calendarListPort;

    // POST /api/google/calendars commits rows in its own request transaction (so @TestTransaction
    // can't roll them back). Clean both before AND after each test so leaked rows never affect this
    // class or any later test class (e.g. GoogleCalendarTest, which assumes a clean table).
    @AfterEach
    @Transactional
    void tearDown() {
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
    }

    // Wipe + seed in a single before-each so a connected credential is always present for POST tests.
    @BeforeEach
    @Transactional
    void setUp() {
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
        GoogleCredential cred = new GoogleCredential();
        cred.ownerId = 1L;
        cred.refreshToken = "rt-resource-test";
        cred.googleSub = "sub-resource-test";
        cred.persist();
    }

    @Test
    void listsGoogleCalendars() {
        Mockito.when(calendarListPort.listCalendars()).thenReturn(List.of(
                new CalendarListPort.RemoteCalendar("work@example.com", "Work"),
                new CalendarListPort.RemoteCalendar("personal@example.com", "Personal")));

        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .when().get("/api/google/calendars")
                .then().statusCode(200)
                .body("googleCalendarId", hasItem("work@example.com"))
                .body("summary", hasItem("Personal"));
    }

    @Test
    void savesReadWriteSelectionAndEnforcesSingleWriteTarget() {
        String writeId = "write-" + System.nanoTime() + "@example.com";
        String readId = "read-" + System.nanoTime() + "@example.com";

        String body = "{\"calendars\":["
                + "{\"googleCalendarId\":\"" + readId + "\",\"summary\":\"Read\",\"readForBusy\":true,\"writeTarget\":false},"
                + "{\"googleCalendarId\":\"" + writeId + "\",\"summary\":\"Write\",\"readForBusy\":false,\"writeTarget\":true}"
                + "]}";

        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .contentType("application/json").body(body)
                .when().post("/api/google/calendars")
                .then().statusCode(200);

        // The write target query returns exactly the one flagged calendar.
        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .when().get("/api/google/calendars/write-target")
                .then().statusCode(200)
                .body("googleCalendarId", is(writeId));
    }

    @Test
    void rejectedSaveDoesNotWipeExistingSelection() {
        // 1) Save a valid selection (one read + one write target).
        String writeId = "keep-write@example.com";
        String body = "{\"calendars\":["
                + "{\"googleCalendarId\":\"" + writeId + "\",\"summary\":\"Write\",\"readForBusy\":true,\"writeTarget\":true}"
                + "]}";
        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .contentType("application/json").body(body)
                .when().post("/api/google/calendars").then().statusCode(200);

        // 2) A subsequent INVALID save (two write targets) must be rejected...
        String bad = "{\"calendars\":["
                + "{\"googleCalendarId\":\"a@example.com\",\"summary\":\"A\",\"readForBusy\":true,\"writeTarget\":true},"
                + "{\"googleCalendarId\":\"b@example.com\",\"summary\":\"B\",\"readForBusy\":true,\"writeTarget\":true}"
                + "]}";
        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .contentType("application/json").body(bad)
                .when().post("/api/google/calendars").then().statusCode(400);

        // 3) ...and the original write target must still be there (delete rolled back).
        given().cookie("quarkus-credential", com.calit.web.FormAuth.login())
                .when().get("/api/google/calendars/write-target")
                .then().statusCode(200)
                .body("googleCalendarId", org.hamcrest.Matchers.is(writeId));
    }
}
