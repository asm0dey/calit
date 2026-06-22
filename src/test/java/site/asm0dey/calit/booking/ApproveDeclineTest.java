package site.asm0dey.calit.booking;

import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApproveDeclineTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant(); // 09:00 local

    @Test
    @TestTransaction
    void approveFlipsToConfirmedAndCreatesEvent() {
        // Feature 14: approve a PENDING request -> CONFIRMED + Google event created now.
        seedSettings();
        approvalType("approve");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), eq(SLOT_09), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ap", "https://meet.google.com/ap-1-2", "h"));

        Booking b = bookingService.book(1L, "approve", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en");
        assertEquals(BookingStatus.PENDING, b.status);

        bookingService.approve(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals("evt-ap", loaded.googleEventId);
        assertEquals("https://meet.google.com/ap-1-2", loaded.meetLink);
        // The event is created at approve time (createMeetLink=true for GOOGLE_MEET), not at book time.
        verify(calendarPort, times(1)).createEvent(anyLong(), anyString(), anyString(), eq(SLOT_09),
                eq(SLOT_09.plusSeconds(3600)), eq(List.of("sam@example.com", "owner@example.com")),
                eq(true), eq(null));
    }

    @Test
    @TestTransaction
    void declineFlipsToDeclinedFreesSlotAndCreatesNoEvent() {
        // Feature 14: decline a PENDING request -> DECLINED, slot freed, no Google event.
        seedSettings();
        MeetingType t = approvalType("decline");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        Booking b = bookingService.book(1L, "decline", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en");
        // While PENDING, the 09:00 slot is held.
        assertTrue(bookingService.availableSlots(t, DAY, DAY).stream()
                .noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));

        bookingService.decline(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.DECLINED, loaded.status);
        verify(calendarPort, never()).createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        // DECLINED leaves the partial constraint -> 09:00 is bookable again.
        List<TimeSlot> avail = bookingService.availableSlots(t, DAY, DAY);
        assertTrue(avail.stream().anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
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

    private MeetingType approvalType(String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = true; // feature 14
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
}
