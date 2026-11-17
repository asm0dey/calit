package site.asm0dey.calit.booking;

import module java.base;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.asm0dey.calit.calendar.CalendarRef;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.domain.MeetingType;

/**
 * {@link BookingService} guest wiring over mocked repositories and ports: no database (#343).
 */
class BookingServiceGuestWiringTest extends BookingServiceWiringFixture {
    @Test
    void declineGuestAddressesTheEventOnTheBookingsStoredCalendarRef() {
        // declineGuest reads the owner id off the guest row and the calendar ref off the booking
        // row -- two different objects. Prove both are wired correctly (not, say, the owner's
        // current write target) by giving the booking a distinct stored ref and asserting the
        // patch is addressed there.
        MeetingType t = type(false);
        slotFree(SLOT_09);
        CalendarRef ref = new CalendarRef(5L, "stored@example.com");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-ref", null, "https://calendar.google.com/evt-ref", ref));

        bookingService.book(
                1L,
                t.slug,
                SLOT_09,
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok-ref",
                "",
                "en",
                List.of("g1@example.com")
        );

        var persisted = ArgumentCaptor.forClass(BookingGuest.class);
        verify(guests).persist(persisted.capture());
        BookingGuest g1 = persisted.getValue();
        when(guests.findByDeclineToken(g1.declineToken)).thenReturn(g1);

        bookingService.declineGuest(g1.declineToken);

        verify(calendarPort, times(1)).updateEvent(eq(1L), eq(ref), eq("evt-ref"), any(), any(), any());
    }
}
