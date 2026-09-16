package site.asm0dey.calit.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.google.CalendarPort;

/**
 * The Google half of invitee erasure, with the calendar port faked: a reachable Google reports
 * REMOVED, and a Google that fails the delete must not stop the erasure — it reports UNREACHABLE
 * and the booking is still cancelled and anonymised.
 */
@QuarkusTest
class ErasureGoogleOutcomeTest {

    private static final String EVENT = "evt-erase";

    @Inject
    PrivacyService privacy;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void connected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
    }

    /** Gives a seeded booking a stored Google event and returns its manage token. */
    private static String withGoogleEvent(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking b = Booking.findById(id);
            b.googleEventId = EVENT;
            b.googleCalendarId = "primary";
            return b.manageToken;
        });
    }

    private static Booking reload(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> Booking.findById(id));
    }

    @Test
    void upcomingBookingWithAWorkingGoogleIsRemoved() {
        var id = ErasureFixtures.seedUpcomingBookingId();
        var token = withGoogleEvent(id);

        ErasureReport report = privacy.eraseByManageToken(token);

        assertEquals(ErasureReport.GoogleOutcome.REMOVED, report.google());
        verify(calendarPort).deleteEvent(eq(ErasureFixtures.OWNER), any(), eq(EVENT));
        Booking b = reload(id);
        assertTrue(b.isErased());
        assertEquals(BookingStatus.CANCELLED, b.status);
    }

    @Test
    void upcomingBookingWhoseGoogleDeleteFailsIsStillCancelledAndErased() {
        var id = ErasureFixtures.seedUpcomingBookingId();
        var token = withGoogleEvent(id);
        doThrow(new RuntimeException("Google is down")).when(calendarPort).deleteEvent(anyLong(), any(), any());

        ErasureReport report = privacy.eraseByManageToken(token);

        assertEquals(ErasureReport.GoogleOutcome.UNREACHABLE, report.google());
        Booking b = reload(id);
        assertTrue(b.isErased(), "a Google failure must not block the erasure");
        assertEquals(BookingStatus.CANCELLED, b.status, "the booking is still cancelled");
        assertEquals("", b.inviteeName);
        assertNull(b.googleEventId);
    }

    @Test
    void pastBookingWithAWorkingGoogleIsRemovedAndItsEventRefsCleared() {
        var id = ErasureFixtures.seedPastBookingId();
        var token = withGoogleEvent(id);

        ErasureReport report = privacy.eraseByManageToken(token);

        assertEquals(ErasureReport.GoogleOutcome.REMOVED, report.google());
        verify(calendarPort).deleteEvent(eq(ErasureFixtures.OWNER), any(), eq(EVENT));
        Booking b = reload(id);
        assertTrue(b.isErased());
        assertEquals(BookingStatus.CONFIRMED, b.status, "a past booking is not cancelled");
        assertNull(b.googleEventId, "an erased row must not keep pointing at the owner's event");
        assertNull(b.googleCalendarId);
        assertNull(b.googleCredentialId);
    }
}
