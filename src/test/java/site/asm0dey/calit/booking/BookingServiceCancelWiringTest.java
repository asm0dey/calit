package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CalendarRef;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.domain.MeetingType;

/**
 * {@link BookingService} cancel wiring over mocked repositories and ports: no database (#343).
 */
class BookingServiceCancelWiringTest extends BookingServiceWiringFixture {
    @Test
    void cancellingTwiceFiresOnceAndDeletesOnce() {
        // #310: a second cancel (a re-clicked manage link) must not re-cancel or resend mail
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-twice", null, "h", null));
        Booking b = book(t);

        bookingService.cancel(b.manageToken);
        bookingService.cancel(b.manageToken);

        verify(cancelled, times(1)).fire(any());
        verify(calendarPort, times(1)).deleteEvent(anyLong(), any(), eq("evt-twice"));
        assertThat(b.status).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancellingADeclinedBookingKeepsItDeclined() {
        MeetingType t = type(true);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = book(t);
        b.status = BookingStatus.DECLINED;

        assertThatCode(() -> bookingService.cancel(b.manageToken)).doesNotThrowAnyException();

        verify(cancelled, never()).fire(any());
        assertThat(b.status).isEqualTo(BookingStatus.DECLINED);
    }

    @Test
    void cancelClearsTheGoogleEventRefs() {
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-refs", "https://meet.google.com/r-e-f", "h", null));
        Booking b = book(t);

        bookingService.cancel(b.manageToken);

        assertThat(b.status).isEqualTo(BookingStatus.CANCELLED);
        // Same post-state the group path leaves behind (deleteGroupGoogleEvent): a cancelled row
        // must not keep pointing at an event that no longer exists on Google.
        assertThat(b.eventId).isNull();
        assertThat(b.meetLink).isNull();
        assertThat(b.calendarRemoteId).isNull();
        assertThat(b.calendarAccountId).isNull();
    }

    @Test
    void cancelClearsTheRefsEvenWhenGoogleIsDisconnected() {
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-off", null, "h", null));
        Booking b = book(t);
        // The owner disconnects Google between booking and cancelling: the remote delete is skipped,
        // but the local refs must still be cleared (mirrors deleteGroupGoogleEvent's javadoc).
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        bookingService.cancel(b.manageToken);

        verify(calendarPort, never()).deleteEvent(anyLong(), any(), eq("evt-off"));
        assertThat(b.eventId).isNull();
        assertThat(b.meetLink).isNull();
    }

    @Test
    void cancelDeletesOnTheStoredCalendar() {
        CalendarRef ref = new CalendarRef(5L, "work@example.com");
        Booking booked = bookAnySlot(ref, "evt-cancel");

        bookingService.cancel(booked.manageToken, true);

        verify(calendarPort).deleteEvent(booked.ownerId, ref, "evt-cancel");
    }

    @Test
    void bookingWithNoReportedAddressStoresNone() {
        // createEvent reports no address, as pre-V26 rows have none
        Booking booked = bookAnySlot(null, "evt-old");

        bookingService.cancel(booked.manageToken, true);

        verify(calendarPort).deleteEvent(eq(booked.ownerId), isNull(), eq("evt-old"));
    }

    /**
     * Google connected, createEvent returning {@code eventId} at {@code address}; Sam books 09:00 on an auto type.
     */
    private Booking bookAnySlot(CalendarRef address, String eventId) {
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent(eventId, null, null, address));
        return book(t);
    }
}
