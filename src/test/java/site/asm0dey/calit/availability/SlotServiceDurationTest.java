package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeDuration;
import site.asm0dey.calit.domain.OwnerSettings;

@QuarkusTest
class SlotServiceDurationTest {

    private static final Long OWNER = 1L;

    @Inject
    SlotService slotService;

    /** A Monday well clear of "now" so min-notice/horizon filters (applied elsewhere) never bite. */
    private static final LocalDate MONDAY = LocalDate.of(2027, 3, 1);

    @Transactional
    MeetingType seed(String slug, int defaultMinutes, List<Integer> extraLengths) {
        return seed(slug, defaultMinutes, extraLengths, null);
    }

    @Transactional
    MeetingType seed(String slug, int defaultMinutes, List<Integer> extraLengths, Integer cadence) {
        // generateRawSlots(..., hostOwnerId, ...) reads OwnerSettings for the host; not reseeded by
        // DatabaseResetCallback, so each test must provide it (mirrors SlotServiceTest#seedSettings).
        OwnerSettings settings = OwnerSettings.forOwner(OWNER);
        if (settings == null) {
            settings = new OwnerSettings();
            settings.ownerId = OWNER;
        }
        settings.ownerName = "Owner";
        settings.ownerEmail = "owner@example.com";
        settings.timezone = "UTC";
        settings.persist();

        MeetingType t = new MeetingType();
        t.ownerId = OWNER;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = defaultMinutes;
        t.slotIntervalMinutes = cadence;
        t.persist();
        for (int len : extraLengths) {
            MeetingTypeDuration d = new MeetingTypeDuration();
            d.meetingTypeId = t.id;
            d.durationMinutes = len;
            d.persist();
        }
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = OWNER;
        r.meetingTypeId = t.id;
        r.dayOfWeek = DayOfWeek.MONDAY;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(12, 0);
        r.persist();
        return t;
    }

    private List<LocalTime> startsFor(MeetingType t, int duration) {
        return slotService.generateRawSlots(t, OWNER, MONDAY, MONDAY, null, duration).stream()
                .map(s -> s.start().toLocalTime())
                .toList();
    }

    @Test
    void theLatticeIsAnchoredToTheShortestLengthNotTheChosenOne() {
        MeetingType t = seed("lattice", 60, List.of(30, 120));

        // Shortest allowed is 30, so candidate starts are every 30 minutes for BOTH picks.
        assertEquals(
                List.of(
                        LocalTime.of(9, 0),
                        LocalTime.of(9, 30),
                        LocalTime.of(10, 0),
                        LocalTime.of(10, 30),
                        LocalTime.of(11, 0),
                        LocalTime.of(11, 30)),
                startsFor(t, 30));

        // 120 keeps the same lattice and simply drops the starts that run past 12:00.
        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0)), startsFor(t, 120));
    }

    @Test
    void aSingleDurationTypeIsUnchangedByTheNewParameter() {
        MeetingType t = seed("single", 45, List.of());
        // Cadence falls back to the shortest allowed, which for a single-duration type IS the duration.
        // 09:00-12:00 is 180 minutes, so four back-to-back 45-min slots fit exactly (the last ending
        // precisely at 12:00), matching the inclusive-end-of-window behavior established by
        // SlotServiceTest#generatesBackToBackSlotsWithinGlobalWindow (120min window / 60min -> 2 slots).
        assertEquals(
                List.of(LocalTime.of(9, 0), LocalTime.of(9, 45), LocalTime.of(10, 30), LocalTime.of(11, 15)),
                startsFor(t, 45));
        // and the old overload agrees with the explicit one
        assertEquals(
                startsFor(t, 45),
                slotService.generateRawSlots(t, OWNER, MONDAY, MONDAY, null).stream()
                        .map(s -> s.start().toLocalTime())
                        .toList());
    }

    @Test
    void anExplicitCadenceStillWins() {
        // slotIntervalMinutes is set INSIDE the seeding transaction — assigning it to a detached
        // entity afterwards would never reach the row SlotService reads back.
        MeetingType t = seed("explicit-cadence", 60, List.of(30), 60);
        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0)), startsFor(t, 30));
    }
}
