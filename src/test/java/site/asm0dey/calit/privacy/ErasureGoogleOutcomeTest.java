package site.asm0dey.calit.privacy;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * Gives a seeded booking a stored Google event and returns its manage token.
     */
    private static String withGoogleEvent(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking b = Booking.findById(id);
            b.googleEventId = EVENT;
            b.googleCalendarId = "primary";
            return b.manageToken;
        });
    }

    private static Booking reload(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.findById(id));
    }

    @Test
    void upcomingBookingWithAWorkingGoogleIsRemoved() {
        var id = ErasureFixtures.seedUpcomingBookingId();
        var token = withGoogleEvent(id);

        ErasureReport report = privacy.eraseByManageToken(token);

        assertThat(report.google()).isEqualTo(ErasureReport.GoogleOutcome.REMOVED);
        verify(calendarPort).deleteEvent(eq(ErasureFixtures.OWNER), any(), eq(EVENT));
        Booking b = reload(id);
        assertThat(b.isErased()).isTrue();
        assertThat(b.status).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void upcomingBookingWhoseGoogleDeleteFailsIsStillCancelledAndErased() {
        var id = ErasureFixtures.seedUpcomingBookingId();
        var token = withGoogleEvent(id);
        doThrow(new RuntimeException("Google is down")).when(calendarPort).deleteEvent(anyLong(), any(), any());

        ErasureReport report = privacy.eraseByManageToken(token);

        assertThat(report.google()).isEqualTo(ErasureReport.GoogleOutcome.UNREACHABLE);
        Booking b = reload(id);
        assertThat(b.isErased()).as("a Google failure must not block the erasure").isTrue();
        assertThat(b.status).as("the booking is still cancelled").isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.inviteeName).isEmpty();
        assertThat(b.googleEventId).isNull();
    }

    @Test
    void pastBookingWithAWorkingGoogleIsRemovedAndItsEventRefsCleared() {
        var id = ErasureFixtures.seedPastBookingId();
        var token = withGoogleEvent(id);

        ErasureReport report = privacy.eraseByManageToken(token);

        assertThat(report.google()).isEqualTo(ErasureReport.GoogleOutcome.REMOVED);
        verify(calendarPort).deleteEvent(eq(ErasureFixtures.OWNER), any(), eq(EVENT));
        Booking b = reload(id);
        assertThat(b.isErased()).isTrue();
        assertThat(b.status).as("a past booking is not cancelled").isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.googleEventId).as("an erased row must not keep pointing at the owner's event").isNull();
        assertThat(b.googleCalendarId).isNull();
        assertThat(b.googleCredentialId).isNull();
    }
}
