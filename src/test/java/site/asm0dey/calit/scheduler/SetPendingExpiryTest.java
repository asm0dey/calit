package site.asm0dey.calit.scheduler;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * A pending Booking set expires as a unit, at its first Booking's start (or its hold, whichever is earlier).
 */
@QuarkusTest
class SetPendingExpiryTest {
    @Inject
    PendingExpiryScheduler scheduler;
    @Inject
    BookingService bookingService;
    @InjectSpy
    EmailService emailService;
    @InjectMock
    CalendarPort calendarPort;

    /**
     * Three PENDING rows of one set, created an hour ago (inside the hold): the first starts inside the
     * scheduler's grace window, the others days away. Returns the ids in start order.
     */
    private static List<Long> seedSet() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            MeetingType t = MultiHostFixtures.meetingType(1L, "set", 30);
            t.requiresApproval = true;
            var setId = UUID.randomUUID();
            var now = Instant.now();
            List<Long> ids = new ArrayList<>();
            for (var start : List.of(now.plusSeconds(10), now.plus(3, ChronoUnit.DAYS), now.plus(4, ChronoUnit.DAYS))) {
                var b = new Booking();
                b.ownerId = 1L;
                b.meetingTypeId = t.id;
                b.inviteeName = "Sam";
                b.inviteeEmail = "sam@x.com";
                b.startUtc = start;
                b.endUtc = start.plus(30, ChronoUnit.MINUTES);
                b.status = BookingStatus.PENDING;
                b.setId = setId;
                b.createdAt = now.minus(1, ChronoUnit.HOURS);
                b.manageToken = UUID.randomUUID().toString();
                b.persist();
                ids.add(b.id);
            }
            return ids;
        });
    }

    private static List<BookingStatus> statuses(List<Long> ids) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> ids
                .stream()
                .map(id -> Booking.<Booking>findById(id).status)
                .toList());
    }

    @Test
    void setExpiresAtFirstStartAsUnit() {
        var ids = seedSet();

        scheduler.expirePendingBookings();

        assertThat(statuses(ids)).containsOnly(BookingStatus.DECLINED);
        verify(emailService, times(1)).enqueueDeclined(anyLong());
        verify(emailService).enqueueDeclined(ids.getFirst());
    }

    @Test
    void lateApproveAfterExpiryIsNoop() {
        var ids = seedSet();
        when(calendarPort.isConnected(1L)).thenReturn(true);
        scheduler.expirePendingBookings();

        bookingService.approve(ids.getLast());

        assertThat(statuses(ids)).containsOnly(BookingStatus.DECLINED);
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }
}
