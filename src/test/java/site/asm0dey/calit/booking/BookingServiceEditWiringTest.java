package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CalendarRef;

/**
 * {@link BookingService} reschedule/updateDetails wiring over mocked repositories and ports: no database (#343).
 */
class BookingServiceEditWiringTest extends BookingServiceWiringFixture {
    @Test
    void rescheduleOfANonApprovalTypePatchesTheStoredCalendar() {
        // The auto-confirm path patches the existing event via updateEvent instead of deleting it, and must
        // address that patch at the booking's *stored* ref, not the owner's current write target.
        CalendarRef ref = new CalendarRef(5L, "work@example.com");
        Booking booked = confirmedWithEvent(false, ref, "evt-resched-auto");
        slotFree(SLOT_10);

        bookingService.reschedule(booked.manageToken, SLOT_10);

        verify(calendarPort).updateEvent(eq(booked.ownerId), eq(ref), eq("evt-resched-auto"), any(), any(), any());
    }

    @Test
    void updateDetailsPatchesTheStoredCalendar() {
        CalendarRef ref = new CalendarRef(5L, "work@example.com");
        Booking booked = confirmedWithEvent(false, ref, "evt-details");

        bookingService.updateDetails(booked.manageToken, "New title", "New description", null, false);

        verify(calendarPort).updateEventDetails(eq(booked.ownerId), eq(ref), eq("evt-details"), any(), any(), any());
    }

    @Test
    void updateDetailsRejectsOverlongTitle() {
        Booking b = confirmedWithEvent(false, null, null);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var title = "x".repeat(201);
        List<String> guestEmails = List.of();
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> bookingService.updateDetails(b.manageToken, title, null, guestEmails, true));
    }
}
