package site.asm0dey.calit.availability;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;

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

        assertThat(slots).hasSize(2);
        assertThat(slots.getFirst().start().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(slots.getFirst().end().toLocalTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(slots.get(1).start().toLocalTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(slots.getFirst().start().getZone()).isEqualTo(ZoneId.of("Europe/Amsterdam"));
    }

    @Test
    @TestTransaction
    void dropsPartialSlotThatDoesNotFit() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "10:30");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertThat(slots).hasSize(1);
        assertThat(slots.getFirst().start().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    @TestTransaction
    void meetingTypeRuleOverridesGlobalForThatDay() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");
        typedRule(t.id, WORKDAY.getDayOfWeek(), "13:00", "14:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertThat(slots).hasSize(1);
        assertThat(slots.getFirst().start().toLocalTime()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    @TestTransaction
    void emitsNothingForDayWithNoRules() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        // no rule for WORKDAY's day-of-week
        globalRule(WORKDAY.getDayOfWeek().plus(1), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertThat(slots).isEmpty();
    }

    /**
     * Issue #127: a type that defines ANY working hours owns its whole week, so a weekday it leaves
     * blank is CLOSED for that type. The old per-weekday fallback let the owner's global grid leak
     * back in, which made a Mon/Tue-only type bookable every day the global grid covered.
     */
    @Test
    @TestTransaction
    void typeWithAnyRuleIsClosedOnTheDaysItLeavesBlank() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        // global opens WORKDAY
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");
        // the type opens only the NEXT day
        typedRule(t.id, WORKDAY.getDayOfWeek().plus(1), "13:00", "14:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertThat(slots).isEmpty();
    }

    /**
     * A type with no rules of its own still inherits the owner's global week (unchanged).
     */
    @Test
    @TestTransaction
    void typeWithoutOwnRulesStillUsesGlobalHours() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertThat(slots).hasSize(2);
    }

    @Test
    @TestTransaction
    void handlesMultipleWindowsSameDay() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "10:00");
        globalRule(WORKDAY.getDayOfWeek(), "14:00", "15:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertThat(slots).hasSize(2);
    }

    @Test
    @TestTransaction
    void slotIntervalNullKeepsBackToBack() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("intro-60", 60);
        globalRule(WORKDAY.getDayOfWeek(), "09:00", "11:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, WORKDAY, WORKDAY);

        assertThat(slots).hasSize(2);
        assertThat(slots.getFirst().start().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(slots.get(1).start().toLocalTime()).isEqualTo(LocalTime.of(10, 0));
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

        assertThat(slots).hasSize(3);
        assertThat(slots.getFirst().start().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(slots.get(1).start().toLocalTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(slots.get(2).start().toLocalTime()).isEqualTo(LocalTime.of(10, 0));
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

        assertThat(slots).hasSize(2);
        assertThat(slots.getFirst().start().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(slots.get(1).start().toLocalTime()).isEqualTo(LocalTime.of(10, 30));
    }

    /**
     * The null-anchor (single-host) path walks candidate starts as INSTANTS
     * ({@code s.plusSeconds(...)}), not host-local minute-of-day. For a window straddling a DST
     * fall-back this matters: 2026-10-25 is Europe/Berlin's fall-back day (clocks go 03:00 CEST ->
     * 02:00 CET at 01:00 UTC), so a 01:00-05:00 local window spans only 4 hours of WALL clock but 5
     * hours of ELAPSED real time, because the 02:00-03:00 hour is walked twice. This is deliberate
     * (ADR-0008): a host who says they are available 01:00-05:00 on a fall-back day genuinely has
     * five hours, and offering only 4 back-to-back slots would under-count real availability.
     */
    @Test
    @TestTransaction
    void aWindowStraddlingAFallBackTransitionCoversTheFullElapsedTime() {
        var fallBackDay = LocalDate.of(2026, 10, 25);
        seedSettings("Europe/Berlin");
        MeetingType t = meetingType("fall-back-60", 60);
        globalRule(fallBackDay.getDayOfWeek(), "01:00", "05:00");

        List<TimeSlot> slots = slotService.generateRawSlots(t, fallBackDay, fallBackDay);

        assertThat(slots).as("5 back-to-back 60-min slots -- the elapsed real time, not the 4h wall-clock span").hasSize(
                5
        );
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
        rule(dow, start, end, null);
    }

    private void typedRule(Long meetingTypeId, DayOfWeek dow, String start, String end) {
        rule(dow, start, end, meetingTypeId);
    }

    private void rule(DayOfWeek dow, String start, String end, Long meetingTypeId) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = meetingTypeId;
        r.persist();
    }
}
