package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

@QuarkusTest
class UpdateDetailsTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    Booking seedConfirmed(String slug) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Type Name";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService
                .availableSlots(t, LocalDate.now(), LocalDate.now().plusDays(14))
                .getFirst();
        return bookingService.book(
                1L, slug, slot.start().toInstant(), "Pat", "pat@example.com", Map.of(), "", "", "en", List.of());
    }

    @Test
    void updateDetailsPersistsOverridesBumpsSequenceAndPatchesGoogle() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ud", null, "h"));
        Booking b = seedConfirmed("upd-1");
        int beforeSeq = b.icsSequence;

        bookingService.updateDetails(b.manageToken, "Roadmap sync", "Q3 planning", List.of(), true);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(b.id));
        assertEquals("Roadmap sync", after.title);
        assertEquals("Q3 planning", after.description);
        assertTrue(after.icsSequence > beforeSeq, "sequence bumped");
        verify(calendarPort, times(1))
                .updateEventDetails(anyLong(), eq("evt-ud"), eq("Roadmap sync with Pat"), eq("Q3 planning"), any());
    }

    @Test
    void updateDetailsReconcilesGuests() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seedConfirmed("upd-2");

        bookingService.updateDetails(b.manageToken, null, null, List.of("ana@example.com"), false);

        List<BookingGuest> guests = QuarkusTransaction.requiringNew().call(() -> BookingGuest.activeForBooking(b.id));
        assertEquals(1, guests.size());
        assertEquals("ana@example.com", guests.getFirst().email);
    }

    @Test
    void updateDetailsBlankTitleClearsOverride() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seedConfirmed("upd-3");
        bookingService.updateDetails(b.manageToken, "First", "d", List.of(), true);

        bookingService.updateDetails(b.manageToken, "   ", "  ", List.of(), true);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(b.id));
        assertNull(after.title, "blank title → null → falls back to type name");
        assertNull(after.description);
    }

    @Test
    void updateDetailsNoOpDoesNotBumpOrPatch() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-noop", null, "h"));
        Booking b = seedConfirmed("upd-4"); // no override, no guests
        int beforeSeq = b.icsSequence;
        clearInvocations(calendarPort);

        // Same as current state: null title/description, empty guest set → true no-op.
        bookingService.updateDetails(b.manageToken, null, null, List.of(), true);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(b.id));
        assertEquals(beforeSeq, after.icsSequence, "no-op must not bump the sequence");
        verify(calendarPort, never()).updateEventDetails(anyLong(), any(), any(), any(), any());
    }

    @Test
    void updateDetailsRejectsOverlongTitle() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = seedConfirmed("upd-5");
        assertThrows(
                BookingValidationException.class,
                () -> bookingService.updateDetails(b.manageToken, "x".repeat(201), null, List.of(), true));
    }
}
