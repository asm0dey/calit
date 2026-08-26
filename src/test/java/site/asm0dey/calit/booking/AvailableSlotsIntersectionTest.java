package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static site.asm0dey.calit.test.MultiHostFixtures.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarUnavailableException;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class AvailableSlotsIntersectionTest {

    @Inject
    BookingService bookingService;

    @Inject
    MeetingHosts meetingHosts;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    /** creator (id 1) free 09-12, cohost free 10-12 -> intersection 10-12. */
    private MeetingType twoHostType() {
        settings(1L, "pasha");
        AppUser v = enabledUser("volodya");
        settings(v.id, "volodya");
        var mon = DayOfWeek.MONDAY;
        rule(1L, mon, 9, 12);
        rule(v.id, mon, 10, 12);
        return acceptedTwoHostType(1L, v.id, "intro", 60, false);
    }

    @Test
    @TestTransaction
    void intersectsHostWindows() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = twoHostType();
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        List<TimeSlot> slots = bookingService.availableSlots(t, mon, mon);
        // 60-min slots in 10:00-12:00 -> 10:00 and 11:00 only (09:00 excluded: cohost busy)
        assertEquals(2, slots.size());
        assertEquals(
                java.time.LocalTime.of(10, 0),
                slots.get(0).start().withZoneSameInstant(AMS).toLocalTime());
    }

    @Test
    @TestTransaction
    void brokenHostCalendarFailsClosedToEmpty() {
        MeetingType t = twoHostType();
        // cohost id is the 2nd host; make its freeBusy throw
        Long cohostId = meetingHosts.hostOwnerIds(t).stream()
                .filter(id -> id != 1L)
                .findFirst()
                .orElseThrow();
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        when(calendarPort.freeBusy(eq(cohostId), any(), any()))
                .thenThrow(new CalendarUnavailableException("needs reconnect"));
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        assertTrue(bookingService.availableSlots(t, mon, mon).isEmpty());
    }

    @Test
    @TestTransaction
    void notBookableTypeYieldsNoSlots() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        MeetingType t = twoHostType();
        MeetingTypeHost.find(t.id, meetingHosts.hostOwnerIds(t).get(1)).status = MeetingTypeHost.PENDING;
        MeetingTypeHost.find(t.id, 1L).persistAndFlush();
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        assertTrue(bookingService.availableSlots(t, mon, mon).isEmpty());
    }

    /**
     * Task 8b: creator window starts 09:00, cohost window starts 09:15 — a non-multiple-of-step
     * offset. Window-anchored grids never share a start instant (creator lands on :00/:30, cohost
     * on :15/:45), so the intersection is empty even though both hosts are free 09:30-11:30/12:00.
     * Day-anchoring (grid multiples of `step` from local midnight) puts both hosts on the same
     * :00/:30 lattice, so the intersection is non-empty and starts at 09:30.
     */
    @Test
    @TestTransaction
    void offsetWindowsStillIntersectWithDayAnchoredGrid() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        settings(1L, "pasha");
        AppUser v = enabledUser("volodya");
        settings(v.id, "volodya");
        var mon = DayOfWeek.MONDAY;
        rule(1L, mon, 9, 12); // creator: 09:00-12:00

        AvailabilityRule cohostRule = new AvailabilityRule();
        cohostRule.ownerId = v.id;
        cohostRule.dayOfWeek = mon;
        cohostRule.startTime = LocalTime.of(9, 15);
        cohostRule.endTime = LocalTime.of(12, 0);
        cohostRule.persist();

        MeetingType t = acceptedTwoHostType(1L, v.id, "intro30", 30, false);
        var monday = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        List<TimeSlot> slots = bookingService.availableSlots(t, monday, monday);

        assertFalse(slots.isEmpty());
        assertEquals(
                LocalTime.of(9, 30),
                slots.get(0).start().withZoneSameInstant(AMS).toLocalTime());
    }

    /**
     * End-to-end cross-timezone coverage for {@link BookingService#availableSlots}: a London host
     * and a Berlin host (a real one-hour UTC offset apart, not just two zone ids on the same
     * offset) on a 45-minute type. Every other test in this class calls
     * {@link site.asm0dey.calit.availability.SlotService#generateRawSlots} per host and intersects
     * by hand, so none of them would notice if {@code latticeZone} (BookingService.java:145) were
     * reverted to {@code null} for the multi-host branch -- this test calls {@code availableSlots}
     * itself, and the 1-hour offset against a 45-minute step (60 is not a multiple of 45) means the
     * two hosts' WINDOW-anchored grids land 15 minutes out of phase, so a null lattice zone here
     * would make the intersection empty (verified manually: temporarily forcing {@code latticeZone
     * = null} at BookingService.java:145 turns this assertion red).
     */
    @Test
    @TestTransaction
    void crossTimezoneAvailableSlotsIsNonEmpty() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var london = ZoneId.of("Europe/London");
        var berlin = ZoneId.of("Europe/Berlin");

        AppUser creator = AppUser.findByUsername("admin");
        settings(creator.id, "london-host").timezone = london.getId();
        AppUser cohost = enabledUser("berlin-cohost");
        settings(cohost.id, "berlin-host").timezone = berlin.getId();

        var mon = DayOfWeek.MONDAY;
        // Both hosts free 09:00-17:00 in their OWN local time -- a real two-hour offset apart.
        rule(creator.id, mon, 9, 17);
        rule(cohost.id, mon, 9, 17);

        MeetingType t = acceptedTwoHostType(creator.id, cohost.id, "cross-tz-45", 45, false);
        var monday = LocalDate.now(london).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        List<TimeSlot> slots = bookingService.availableSlots(t, monday, monday);
        assertFalse(slots.isEmpty(), "a London host and a Berlin host must share some overlapping availability");
    }
}
