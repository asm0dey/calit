package com.calit.availability;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SlotServiceTest {

    @Inject
    SlotService slotService;

    private static final LocalDate WORKDAY = LocalDate.of(2026, 6, 8);

    @Test
    @TestTransaction
    void generatesBackToBackSlotsWithinGlobalWindow() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(0).end().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(1).start().toLocalTime());
        assertEquals(ZoneId.of("Europe/Amsterdam"), slots.get(0).start().getZone());
    }

    @Test
    @TestTransaction
    void dropsPartialSlotThatDoesNotFit() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "10:30");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void meetingTypeRuleOverridesGlobalForThatDay() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");
        typedRule(t.id, WORKDAY.getDayOfWeek(), "13:00", "14:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(13, 0), slots.get(0).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void emitsNothingForDayWithNoRules() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        // no rule for WORKDAY's day-of-week
        globalRule(WORKDAY.getDayOfWeek().plus(1), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertTrue(slots.isEmpty());
    }

    @Test
    @TestTransaction
    void handlesMultipleWindowsSameDay() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "10:00");
        globalRule(WORKDAY.getDayOfWeek(), "14:00", "15:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
    }

    @Test
    @TestTransaction
    void slotIntervalNullKeepsBackToBack() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(1).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void slotIntervalSmallerThanDurationOverlaps() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        t.slotIntervalMinutes = 30;
        t.persist();
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(3, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
        assertEquals(LocalTime.of(9, 30), slots.get(1).start().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(2).start().toLocalTime());
    }

    @Test
    @TestTransaction
    void slotIntervalLargerThanDurationLeavesGap() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        t.slotIntervalMinutes = 90;
        t.persist();
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "12:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.get(0).start().toLocalTime());
        assertEquals(LocalTime.of(10, 30), slots.get(1).start().toLocalTime());
    }

    @Test
    void effectiveSlotIntervalFallsBackToDurationWhenUnset() {
        MeetingType t = new MeetingType();
        t.durationMinutes = 60;
        assertEquals(60, t.effectiveSlotIntervalMinutes());
        t.slotIntervalMinutes = 30;
        assertEquals(30, t.effectiveSlotIntervalMinutes());
    }

    // --- helpers ---

    private void seedSettings(String zone) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = zone;
        s.persist();
    }

    private MeetingType meetingType(String slug, int minutes) {
        MeetingType t = new MeetingType();
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.persist();
        return t;
    }

    private void globalRule(DayOfWeek dow, String start, String end) {
        rule(dow, start, end, null);
    }

    private void typedRule(Long meetingTypeId, DayOfWeek dow, String start, String end) {
        rule(dow, start, end, meetingTypeId);
    }

    private void rule(DayOfWeek dow, String start, String end, Long meetingTypeId) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = meetingTypeId;
        r.persist();
    }
}
