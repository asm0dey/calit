package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.BusyInterval;
import site.asm0dey.calit.google.CalendarPort;

@QuarkusTest
class AvailableSlotsTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    // Owner tz Europe/Amsterdam. Derive a future weekday from now() so slots never fall in the past.
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY =
            Instant.now().atZone(ZONE).toLocalDate().plusDays(7);

    @Test
    @TestTransaction
    void noBusyLeavesAllRawSlotsIntact() {
        seedSettings();
        MeetingType t = meetingType("avail-nobusy", 60, 0, 0);
        globalRule(DAY.getDayOfWeek(), "09:00", "11:00"); // two 60-min raw slots
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), eq(anyMonday()), eq(anyMondayEnd())))
                .thenReturn(List.of());

        List<TimeSlot> slots = bookingService.availableSlots(t, DAY, DAY);

        assertEquals(2, slots.size());
    }

    @Test
    @TestTransaction
    void busyOverlappingSlotRemovesIt() {
        seedSettings();
        MeetingType t = meetingType("avail-overlap", 60, 0, 0);
        globalRule(DAY.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        // Busy 09:15-09:45 local, overlaps the 09:00 slot only.
        when(calendarPort.freeBusy(anyLong(), eq(anyMonday()), eq(anyMondayEnd())))
                .thenReturn(List.of(new BusyInterval(
                        DAY.atTime(9, 15).atZone(ZONE).toInstant(),
                        DAY.atTime(9, 45).atZone(ZONE).toInstant())));

        List<TimeSlot> slots = bookingService.availableSlots(t, DAY, DAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.getFirst().start().toLocalTime());
    }

    @Test
    @TestTransaction
    void busyInsideBufferZoneRemovesAdjacentSlot() {
        seedSettings();
        // 60-min slots, 15-min buffer AFTER. 09:00-10:00 slot's buffered end is 10:15 local.
        MeetingType t = meetingType("avail-buffer", 60, 0, 15);
        globalRule(DAY.getDayOfWeek(), "09:00", "10:00"); // exactly one raw slot 09:00-10:00
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        // Busy 10:05-10:10 local: outside the slot, INSIDE the 15-min after-buffer.
        when(calendarPort.freeBusy(anyLong(), eq(anyMonday()), eq(anyMondayEnd())))
                .thenReturn(List.of(new BusyInterval(
                        DAY.atTime(10, 5).atZone(ZONE).toInstant(),
                        DAY.atTime(10, 10).atZone(ZONE).toInstant())));

        List<TimeSlot> slots = bookingService.availableSlots(t, DAY, DAY);

        assertTrue(slots.isEmpty(), "buffer-zone busy must remove the adjacent slot (feature 6)");
    }

    @Test
    @TestTransaction
    void existingConfirmedBookingBlocksItsSlot() {
        seedSettings();
        MeetingType t = meetingType("avail-booked", 60, 0, 0);
        globalRule(DAY.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), eq(anyMonday()), eq(anyMondayEnd())))
                .thenReturn(List.of());
        // Confirmed booking 09:00-10:00 local blocks the first slot.
        persistBooking(
                DAY.atTime(9, 0).atZone(ZONE).toInstant(),
                DAY.atTime(10, 0).atZone(ZONE).toInstant(),
                BookingStatus.CONFIRMED);

        List<TimeSlot> slots = bookingService.availableSlots(t, DAY, DAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.getFirst().start().toLocalTime());
    }

    @Test
    @TestTransaction
    void pendingBookingBlocksItsSlot() {
        // Feature 14: a PENDING approval hold blocks its slot just like CONFIRMED does.
        seedSettings();
        MeetingType t = meetingType("avail-pending", 60, 0, 0);
        globalRule(DAY.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), eq(anyMonday()), eq(anyMondayEnd())))
                .thenReturn(List.of());
        // PENDING booking 09:00-10:00 local blocks the first slot.
        persistBooking(
                DAY.atTime(9, 0).atZone(ZONE).toInstant(),
                DAY.atTime(10, 0).atZone(ZONE).toInstant(),
                BookingStatus.PENDING);

        List<TimeSlot> slots = bookingService.availableSlots(t, DAY, DAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.getFirst().start().toLocalTime());
    }

    @Test
    @TestTransaction
    void degradedModeUsesOnlyBookingBusyAndNeverCallsFreeBusy() {
        // Degraded mode: Google not connected -> freeBusy is never called; busy is internal bookings only.
        seedSettings();
        MeetingType t = meetingType("avail-degraded", 60, 0, 0);
        globalRule(DAY.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        persistBooking(
                DAY.atTime(9, 0).atZone(ZONE).toInstant(),
                DAY.atTime(10, 0).atZone(ZONE).toInstant(),
                BookingStatus.CONFIRMED);

        List<TimeSlot> slots = bookingService.availableSlots(t, DAY, DAY);

        assertEquals(1, slots.size());
        assertEquals(LocalTime.of(10, 0), slots.getFirst().start().toLocalTime());
        // freeBusy must NOT be consulted when disconnected.
        verify(calendarPort, never()).freeBusy(anyLong(), any(), any());
    }

    @Test
    @TestTransaction
    void minNoticeDropsNearTermSlots() {
        // Feature 11: a huge min-notice (relative to now) drops every slot near today.
        // Use a date derived from "now" in the owner tz so the assertion holds on any run date.
        seedSettings();
        var zone = ZoneId.of("Europe/Amsterdam");
        var someday = Instant.now().atZone(zone).toLocalDate().plusDays(3);
        MeetingType t = meetingType("avail-minnotice", 60, 0, 0);
        t.minNoticeMinutes = 60 * 24 * 365 * 50; // ~50 years -> well past any near-term slot
        t.persist();
        // Pick a weekday rule that covers the chosen date.
        globalRule(someday.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        List<TimeSlot> slots = bookingService.availableSlots(t, someday, someday);

        assertTrue(slots.isEmpty(), "min-notice must drop slots earlier than now + minNoticeMinutes");
    }

    @Test
    @TestTransaction
    void horizonDropsFarFutureSlots() {
        // Feature 11: a 1-day horizon drops a slot ~30 days out from "now".
        seedSettings();
        var zone = ZoneId.of("Europe/Amsterdam");
        var farDay = Instant.now().atZone(zone).toLocalDate().plusDays(30);
        MeetingType t = meetingType("avail-horizon", 60, 0, 0);
        t.horizonDays = 1; // only ~tomorrow is bookable; a 30-day-out slot is past the horizon
        t.persist();
        globalRule(farDay.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        List<TimeSlot> slots = bookingService.availableSlots(t, farDay, farDay);

        assertTrue(slots.isEmpty(), "horizon must drop slots later than now + horizonDays");
    }

    @Test
    @TestTransaction
    void excludeBookingIdFreesThatBookingsSlot() {
        seedSettings();
        MeetingType t = meetingType("avail-exclude", 60, 0, 0);
        globalRule(DAY.getDayOfWeek(), "09:00", "11:00");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), eq(anyMonday()), eq(anyMondayEnd())))
                .thenReturn(List.of());
        Booking b = persistBooking(
                DAY.atTime(9, 0).atZone(ZONE).toInstant(),
                DAY.atTime(10, 0).atZone(ZONE).toInstant(),
                BookingStatus.CONFIRMED);

        // Excluding b: both slots available again.
        List<TimeSlot> slots = bookingService.availableSlots(t, DAY, DAY, b.id);
        assertEquals(2, slots.size());
        assertTrue(slots.stream().anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
        // Without exclusion the 09:00 slot is blocked.
        assertFalse(bookingService.availableSlots(t, DAY, DAY).stream()
                .anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
    }

    // --- helpers ---

    // The from/to window availableSlots computes for the single DAY (start-of-day in ZONE to next).
    private static Instant anyMonday() {
        return DAY.atStartOfDay(ZONE).toInstant();
    }

    private static Instant anyMondayEnd() {
        return DAY.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    private void seedSettings() {
        // Idempotent upsert: a non-@TestTransaction REST test (MeetingTypeResourceTest PUT /api/settings)
        // may have committed the singleton row before this suite runs, so reuse it if present rather
        // than re-inserting the same primary key (which would violate owner_settings_pkey).
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    private MeetingType meetingType(String slug, int minutes, int bufBefore, int bufAfter) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.bufferBeforeMinutes = bufBefore;
        t.bufferAfterMinutes = bufAfter;
        // Wide window so min-notice/horizon never drop the now-relative DAY (7 days out) test slots.
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000; // ~136 years: keeps the DAY slot comfortably inside the horizon
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = false;
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

    private Booking persistBooking(Instant start, Instant end, BookingStatus status) {
        // The booking.meeting_type_id FK requires a real MeetingType row; seed a throwaway one.
        MeetingType holder = new MeetingType();
        holder.ownerId = 1L;
        holder.name = "holder-" + UUID.randomUUID();
        holder.slug = "holder-" + UUID.randomUUID();
        holder.durationMinutes = 60;
        holder.minNoticeMinutes = 0;
        holder.horizonDays = 50_000;
        holder.locationType = LocationType.GOOGLE_MEET;
        holder.requiresApproval = false;
        holder.persist();
        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = holder.id;
        b.inviteeName = "Existing";
        b.inviteeEmail = "existing@example.com";
        b.startUtc = start;
        b.endUtc = end;
        b.status = status;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.persist();
        return b;
    }
}
