package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CreatedEvent;

/**
 * {@link BookingService} approve/decline wiring over mocked repositories and ports: no database (#343).
 */
class BookingServiceApprovalWiringTest extends BookingServiceWiringFixture {
    @Test
    void approveFlipsToConfirmedAndCreatesEvent() {
        // Feature 14: approve a PENDING request -> CONFIRMED + Google event created now.
        Booking loaded = pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-ap", "https://meet.google.com/ap-1-2", "h", null));

        bookingService.approve(BOOKING_ID);

        assertThat(loaded.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(loaded.eventId).isEqualTo("evt-ap");
        assertThat(loaded.meetLink).isEqualTo("https://meet.google.com/ap-1-2");
        // The event is created at approve time (createMeetLink=true for GOOGLE_MEET), not at book time.
        verify(calendarPort, times(1))
            .createEvent(
                    anyLong(),
                    any(),
                    any(),
                    anyString(),
                    anyString(),
                    eq(SLOT_09),
                    eq(SLOT_09.plusSeconds(3600)),
                    eq(List.of("sam@example.com", "owner@example.com")),
                    eq(true),
                    eq(null)
            );
    }

    @Test
    void approveOnCancelledBookingIsNoOp() {
        // calit-wsab: a stale /me/pending tab must not resurrect a cancelled request.
        Booking b = pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        b.status = BookingStatus.CANCELLED;

        bookingService.approve(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.CANCELLED);
        verifyNoEventCreated();
    }

    @Test
    void approveOnDeclinedBookingIsNoOp() {
        Booking b = pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        bookingService.decline(BOOKING_ID);

        bookingService.approve(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.DECLINED);
        verifyNoEventCreated();
    }

    @Test
    void approveOnConfirmedBookingIsNoOp() {
        // Double-click on Approve: the second click must not create a second Google event.
        pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-once", "https://meet.google.com/x", "h", null));
        bookingService.approve(BOOKING_ID);

        bookingService.approve(BOOKING_ID);

        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void declineOnConfirmedBookingIsNoOp() {
        // A confirmed meeting is cancelled (Google event deleted, cancel email), never "declined".
        Booking b = pendingBooking();
        bookingService.approve(BOOKING_ID);

        bookingService.decline(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void declineOnCancelledBookingIsNoOp() {
        Booking b = pendingBooking();
        b.status = BookingStatus.CANCELLED;

        bookingService.decline(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.CANCELLED);
    }
}
