package site.asm0dey.calit.availability;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static site.asm0dey.calit.test.MultiHostFixtures.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class SlotServicePerHostTest {
    @Inject
    SlotService slotService;

    @Test
    @TestTransaction
    void rawSlotsUseHostWindowsNotCreatorWindows() {
        // creator = admin id 1 (no availability), cohost has Monday 09:00-10:00
        AppUser cohost = enabledUser("volodya");
        settings(cohost.id, "V");

        MeetingType t = meetingType(1L, "intro", 30);
        t.horizonDays = 50000;
        t.persist();

        var monday = LocalDate
            .now(ZoneId.of("Europe/Amsterdam"))
            .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));
        rule(cohost.id, DayOfWeek.MONDAY, 9, 10);

        List<TimeSlot> slots = slotService.generateRawSlots(t, cohost.id, monday, monday);
        // 09:00 and 09:30 (30-min grid within 09:00-10:00)
        assertEquals(2, slots.size());
    }

    /**
     * Task 8b regression: single-host slot generation stays window-anchored (byte-identical),
     * never day-anchored. A window starting 09:15 (not a multiple of the 30-min step from
     * midnight) must still offer 09:15 as its first slot.
     */
    @Test
    @TestTransaction
    void singleHostGridStaysWindowAnchored() {
        AppUser cohost = enabledUser("volodya");
        settings(cohost.id, "V");

        MeetingType t = meetingType(1L, "intro30", 30);
        t.horizonDays = 50000;
        t.persist();

        var monday = LocalDate
            .now(ZoneId.of("Europe/Amsterdam"))
            .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = cohost.id;
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 15);
        r.endTime = LocalTime.of(10, 0);
        r.persist();

        List<TimeSlot> slots = slotService.generateRawSlots(t, cohost.id, monday, monday);
        assertEquals(LocalTime.of(9, 15), slots.get(0).start().toLocalTime());
    }
}
