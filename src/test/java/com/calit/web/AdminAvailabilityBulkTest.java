package com.calit.web;

import com.calit.domain.AvailabilityRule;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AdminAvailabilityBulkTest {

    private static long globalCount(DayOfWeek day) {
        return AvailabilityRule.count("meetingTypeId is null and dayOfWeek = ?1", day);
    }

    @Test
    void bulkSaveReplacesAllGlobalRules() {
        String cred = FormAuth.login();

        // Seed a stale global rule (legacy single-add endpoint) that the bulk save must wipe.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", "SUNDAY")
                .formParam("startTime", "08:00").formParam("endTime", "09:00")
                .formParam("meetingTypeId", "")
                .when().post("/me/availability").then().statusCode(200);
        assertEquals(1, globalCount(DayOfWeek.SUNDAY));

        // Bulk replace: two Monday frames, nothing else.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY", "MONDAY")
                .formParam("frameStart", "09:00", "13:00")
                .formParam("frameEnd", "12:00", "17:00")
                .when().post("/me/availability/bulk").then().statusCode(200);

        assertEquals(0, globalCount(DayOfWeek.SUNDAY), "stale rule should be wiped");
        assertEquals(2, globalCount(DayOfWeek.MONDAY), "two new Monday frames");
    }

    @Test
    void bulkSaveSkipsBlankAndInvertedFrames() {
        String cred = FormAuth.login();
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "TUESDAY", "TUESDAY", "TUESDAY")
                .formParam("frameStart", "09:00", "", "17:00") // blank start; inverted end<=start
                .formParam("frameEnd", "12:00", "12:00", "09:00")
                .when().post("/me/availability/bulk").then().statusCode(200);
        assertEquals(1, globalCount(DayOfWeek.TUESDAY), "only the valid frame persists");
    }
}
