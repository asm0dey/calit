package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

@QuarkusTest
class RescheduleCancelTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY =
            Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant(); // 09:00 local
    private static final Instant SLOT_10 = DAY.atTime(10, 0).atZone(ZONE).toInstant(); // 10:00 local

    @Test
    @TestTransaction
    void rescheduleAutoTypeMovesBookingCallsUpdateAndFreesOldTime() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("resched", false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-r", "https://meet.google.com/r-r-r", "h"));

        Booking b = bookingService.book(
                1L, "resched", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        // Reschedule is keyed by the invitee's manage-token, not the numeric id.
        bookingService.reschedule(b.manageToken, SLOT_10);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals(SLOT_10, loaded.startUtc);
        assertEquals(SLOT_10.plusSeconds(3600), loaded.endUtc);
        verify(calendarPort, times(1))
                .updateEvent(anyLong(), eq("evt-r"), eq(SLOT_10), eq(SLOT_10.plusSeconds(3600)), any());

        // Old 09:00 time is free again; new 10:00 time is now taken.
        List<TimeSlot> avail = bookingService.availableSlots(t, DAY, DAY);
        assertTrue(avail.stream().anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
        assertTrue(avail.stream().noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(10, 0))));
    }

    @Test
    @TestTransaction
    void rescheduleApprovalTypeReturnsToPendingAndDeletesEvent() {
        // Feature 14: rescheduling an approval-type booking re-enters the approval queue (PENDING),
        // deletes any existing Google event, and fires a re-request (not updateEvent).
        seedSettings();
        MeetingType t = approvalType("resched-approval");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        // Book PENDING, then approve so it has a CONFIRMED Google event to delete on reschedule.
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ra", "https://meet.google.com/ra-1-2", "h"));
        Booking b = bookingService.book(
                1L, "resched-approval", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
        bookingService.approve(b.id);

        bookingService.reschedule(b.manageToken, SLOT_10);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.PENDING, loaded.status, "approval type re-enters PENDING on reschedule");
        assertEquals(SLOT_10, loaded.startUtc);
        assertNull(loaded.googleEventId, "the prior event is deleted on re-request");
        assertNull(loaded.meetLink);
        verify(calendarPort, times(1)).deleteEvent(anyLong(), eq("evt-ra"));
        verify(calendarPort, never()).updateEvent(anyLong(), any(), any(), any(), any());
    }

    @Test
    @TestTransaction
    void rescheduleToSameTimeIsNoOp() {
        seedSettings();
        meetingTypeWithMondayWindow("resched-noop", false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-nn", "https://meet.google.com/n-n-n", "h"));

        Booking b = bookingService.book(
                1L, "resched-noop", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
        int beforeSeq = b.icsSequence;
        clearInvocations(calendarPort);

        // Reschedule to the SAME start with guests untouched (null) -> no-op.
        bookingService.reschedule(b.manageToken, SLOT_09, null, false);

        Booking loaded = Booking.findById(b.id);
        assertEquals(SLOT_09, loaded.startUtc);
        assertEquals(beforeSeq, loaded.icsSequence, "no-op must not bump the sequence");
        verify(calendarPort, never()).updateEvent(anyLong(), any(), any(), any(), any());
    }

    @Test
    @TestTransaction
    void cancelFlipsStatusCallsDeleteAndFreesSlot() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("cancel", false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-c", "https://meet.google.com/c-c-c", "h"));

        Booking b = bookingService.book(
                1L, "cancel", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
        assertTrue(bookingService.availableSlots(t, DAY, DAY).stream()
                .noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));

        // Cancel is keyed by the manage-token.
        bookingService.cancel(b.manageToken);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CANCELLED, loaded.status);
        verify(calendarPort, times(1)).deleteEvent(anyLong(), eq("evt-c"));
        // 09:00 slot is bookable again.
        assertTrue(bookingService.availableSlots(t, DAY, DAY).stream()
                .anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
    }

    @Test
    @TestTransaction
    void rescheduleSyncsAttendeesWhenGuestRemoved() {
        seedSettings();
        meetingTypeWithMondayWindow("resched-guest", false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-rg", null, "https://calendar.google.com/evt-rg"));

        Booking b = bookingService.book(
                1L,
                "resched-guest",
                SLOT_09,
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok-rg",
                "",
                "en",
                List.of("g1@example.com"));

        // reschedule to SLOT_10 with NO guests → g1 removed
        bookingService.reschedule(b.manageToken, SLOT_10, List.of());

        verify(calendarPort, times(1))
                .updateEvent(
                        anyLong(),
                        eq("evt-rg"),
                        eq(SLOT_10),
                        eq(SLOT_10.plusSeconds(3600)),
                        eq(List.of("sam@example.com", "owner@example.com")));
    }

    // --- helpers ---

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

    private MeetingType meetingTypeWithMondayWindow(String slug, boolean requiresApproval) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = requiresApproval;
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = DAY.getDayOfWeek();
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        return t;
    }

    private MeetingType approvalType(String slug) {
        return meetingTypeWithMondayWindow(slug, true);
    }
}
