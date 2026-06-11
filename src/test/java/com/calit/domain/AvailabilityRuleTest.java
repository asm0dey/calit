package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AvailabilityRuleTest {

    @Test
    @TestTransaction
    void separatesGlobalRulesFromMeetingTypeRules() {
        // A typed rule's meeting_type_id is a real FK, so persist a MeetingType first
        // and use its generated id (a literal id would violate the FK constraint).
        MeetingType type = new MeetingType();
        type.name = "Sep Test";
        type.slug = "avail-sep-type";
        type.durationMinutes = 30;
        type.persist();

        AvailabilityRule global = rule(DayOfWeek.MONDAY, "09:00", "12:00", null);
        global.persist();
        AvailabilityRule typed = rule(DayOfWeek.MONDAY, "13:00", "14:00", type.id);
        typed.persist();

        List<AvailabilityRule> globals = AvailabilityRule.globalForOwner(1L, DayOfWeek.MONDAY);
        List<AvailabilityRule> typedRules = AvailabilityRule.forMeetingType(1L, type.id, DayOfWeek.MONDAY);

        assertEquals(1, globals.size());
        assertEquals(LocalTime.of(9, 0), globals.get(0).startTime);
        assertEquals(1, typedRules.size());
        assertEquals(LocalTime.of(13, 0), typedRules.get(0).startTime);
        assertTrue(AvailabilityRule.forMeetingType(1L, type.id, DayOfWeek.TUESDAY).isEmpty());
    }

    private AvailabilityRule rule(DayOfWeek dow, String start, String end, Long meetingTypeId) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = meetingTypeId;
        return r;
    }
}
