package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class GroupApprovalTest {

    @Inject
    BookingService bookingService;

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
    void confirmsOnlyAfterEveryHostApproves() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt", "meet", "cal", null));
        type(true);

        Booking lead = bookingService.book(
                1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());

        bookingService.approve(rows.get(0).id); // first host approves
        assertEquals(BookingStatus.CONFIRMED, Booking.<Booking>findById(rows.get(0).id).status);
        verify(calendarPort, never())
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        bookingService.approve(rows.get(1).id); // last host approves -> event + confirm
        Booking.<Booking>group(lead.groupId).forEach(r -> assertEquals(BookingStatus.CONFIRMED, r.status));
        verify(calendarPort, times(1))
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void doubleApproveOnAlreadyConfirmedGroupRowDoesNotDuplicateEvent() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt", "meet", "cal", null));
        type(true);

        Booking lead = bookingService.book(
                1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());

        bookingService.approve(rows.get(0).id); // first host approves
        bookingService.approve(rows.get(1).id); // last host approves -> event created + group confirmed
        verify(calendarPort, times(1))
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        // Double-submit: re-approve an already-CONFIRMED row (double-click / back-button replay).
        // Must NOT re-run createGroupGoogleEvent -> still exactly ONE event, not two.
        bookingService.approve(rows.get(1).id);
        verify(calendarPort, times(1))
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void doubleDeclineOnAlreadyDeclinedGroupIsNoOp() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        type(true);

        Booking lead = bookingService.book(
                1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());

        bookingService.decline(rows.get(0).id);
        Booking.<Booking>group(lead.groupId).forEach(r -> assertEquals(BookingStatus.DECLINED, r.status));

        // Double-submit: decline an already-DECLINED row again -> no-op.
        bookingService.decline(rows.get(1).id);
        Booking.<Booking>group(lead.groupId).forEach(r -> assertEquals(BookingStatus.DECLINED, r.status));
        verify(calendarPort, never())
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void anyDeclineKillsWholeGroup() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        type(true);

        Booking lead = bookingService.book(
                1L, "intro", nextMonday10(), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());

        bookingService.decline(rows.get(0).id);

        Booking.<Booking>group(lead.groupId).forEach(r -> assertEquals(BookingStatus.DECLINED, r.status));
        verify(calendarPort, never())
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }
}
