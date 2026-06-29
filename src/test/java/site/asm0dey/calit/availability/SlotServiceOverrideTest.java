package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class SlotServiceOverrideTest {

    @Inject
    SlotService slotService;

    private static final LocalDate WORKDAY = LocalDate.of(2026, 6, 8); // Monday

    @Test
    @TestTransaction
    void overrideWindowReplacesWeeklyHoursForThatDate() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("ov-replace", 60);
        // Weekly rule says 09:00-11:00 (would be 2 slots).
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");
        // Override for this exact date says a single 13:00-14:00 window (1 slot), replacing weekly.
        DateOverride o = override(t.id, WORKDAY);
        o.persist();
        window(o, "13:00", "14:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(13, 0), slots.getFirst().start().toLocalTime());
    }

    @Test
    @TestTransaction
    void emptyWindowOverrideYieldsZeroSlotsEvenWithWeeklyRule() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("ov-dayoff", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00"); // weekly rule exists
        DateOverride dayOff = override(t.id, WORKDAY);
        dayOff.persist(); // empty windows = day off

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertTrue(slots.isEmpty());
    }

    @Test
    @TestTransaction
    void dateWithoutOverrideStillUsesWeeklyRules() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("ov-fallback", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00"); // no override -> weekly applies

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertEquals(2, slots.size());
        assertEquals(LocalTime.of(9, 0), slots.getFirst().start().toLocalTime());
        assertEquals(LocalTime.of(10, 0), slots.get(1).start().toLocalTime());
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
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.persist();
        return t;
    }

    private void globalRule(DayOfWeek dow, String start, String end) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = null;
        r.persist();
    }

    private DateOverride override(Long meetingTypeId, LocalDate date) {
        DateOverride o = new DateOverride();
        o.ownerId = 1L;
        o.meetingTypeId = meetingTypeId;
        o.overrideDate = date;
        return o;
    }

    private void window(DateOverride parent, String start, String end) {
        DateOverrideWindow w = new DateOverrideWindow();
        w.dateOverrideId = parent.id;
        w.startTime = LocalTime.parse(start);
        w.endTime = LocalTime.parse(end);
        w.persist();
    }
}
