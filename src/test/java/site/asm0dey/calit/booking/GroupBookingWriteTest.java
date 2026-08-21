package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class GroupBookingWriteTest {

    @Inject
    BookingService bookingService;

    @Inject
    MeetingHosts meetingHosts;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private Instant nextMonday10() {
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(10, 0).atZone(AMS).toInstant();
    }

    /** Admin (id 1, "pasha") as creator + a second accepted co-host ("volodya"), both with rules covering Monday. */
    private MeetingType type(boolean approval) {
        MultiHostFixtures.settings(1L, "pasha");
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.settings(v.id, "volodya");
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
        return MultiHostFixtures.acceptedTwoHostType(1L, v.id, "intro", 60, approval);
    }

    @Test
    @TestTransaction
    void autoConfirmWritesNRowsAndOneGoogleEvent() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(
                        anyLong(), any(), anyString(), anyString(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", "https://meet/x", "https://cal/x", null));
        type(false);

        Booking lead = bookingService.book(
                1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());

        assertNotNull(lead.groupId);
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());
        rows.forEach(r -> assertEquals(BookingStatus.CONFIRMED, r.status));
        // exactly one Google event, on the creator (organizer), with both hosts + invitee as attendees
        verify(calendarPort, times(1))
                .createEvent(
                        eq(1L),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        argThat(a ->
                                a.contains("sam@x.com") && a.contains("pasha@x.com") && a.contains("volodya@x.com")),
                        eq(true),
                        any());
        Booking organizerRow = Booking.leadOfGroup(lead.groupId, 1L);
        assertEquals("evt-1", organizerRow.googleEventId);
    }

    @Test
    @TestTransaction
    void autoConfirmCohostOrganizerPutsEventOnCohostAndMirrorsLinkToLead() {
        MeetingType t = type(false);
        long cohostId = meetingHosts.hostOwnerIds(t).stream()
                .filter(id -> id != 1L)
                .findFirst()
                .orElseThrow();
        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        when(calendarPort.createEvent(
                        anyLong(), any(), anyString(), anyString(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", "https://meet/x", "https://cal/x", null));

        Booking lead = bookingService.book(
                1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());

        // event created on the co-host (organizer), not on the creator
        verify(calendarPort, times(1))
                .createEvent(
                        eq(cohostId), any(), anyString(), anyString(), any(), any(), anyList(), anyBoolean(), any());
        Booking cohostRow = Booking.<Booking>group(lead.groupId).stream()
                .filter(r -> r.ownerId == cohostId)
                .findFirst()
                .orElseThrow();
        assertEquals("evt-1", cohostRow.googleEventId);
        Booking leadRow = Booking.leadOfGroup(lead.groupId, 1L);
        assertEquals("https://meet/x", leadRow.meetLink);
        assertNull(leadRow.googleEventId);
    }

    @Test
    @TestTransaction
    void approvalTypeWritesNPendingRowsNoEvent() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        type(true);
        Booking lead = bookingService.book(
                1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());
        rows.forEach(r -> {
            assertEquals(BookingStatus.PENDING, r.status);
            assertNotNull(r.approvalToken);
        });
        verify(calendarPort, never())
                .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }
}
