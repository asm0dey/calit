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
import site.asm0dey.calit.booking.CaptchaVerifier;
import site.asm0dey.calit.booking.SlotPick;
import site.asm0dey.calit.calendar.CreatedEvent;
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
    @InjectMock
    CaptchaVerifier captchaVerifier;

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
        verify(emailService, times(1)).enqueueDeclined(anyLong(), anyList());
        verify(emailService).enqueueDeclined(ids.getFirst(), ids);
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

    /**
     * An approved 10/11/12 set whose 11:00 session the Invitee moved to 14:00 (back to PENDING) long after booking:
     * expiry declines that one Booking and leaves the confirmed ones, their Mirror events and reminders alone.
     */
    @Test
    void expiryDeclinesOnlyRescheduledSessionOfApprovedSet() {
        var zone = ZoneId.of("Europe/Amsterdam");
        var monday = LocalDate.now(zone).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.createEvent(
                anyLong(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyList(),
                anyBoolean(),
                any()
        ))
            .thenReturn(new CreatedEvent("evt", null, null, null));
        QuarkusTransaction.requiringNew().run(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MeetingType t = MultiHostFixtures.meetingType(1L, "set", 30);
            t.requiresApproval = true;
            t.multiSlotMin = 2;
            t.multiSlotMax = 3;
            t.multiSlotSameDay = true;
        });
        var picks =
                Stream
            .of(10, 11, 12)
            .map(h -> new SlotPick(monday.atTime(h, 0).atZone(zone).toInstant(), 30))
            .toList();
        var rows = bookingService.bookSet(
                1L,
                "set",
                picks,
                "Sam",
                "sam@x.com",
                Map.of(),
                "tok",
                null,
                "",
                "en",
                List.of(),
                null
        );
        bookingService.approve(rows.getFirst().id);
        bookingService.reschedule(rows.get(1).manageToken, monday.atTime(14, 0).atZone(zone).toInstant());
        var ids = rows.stream().map(b -> b.id).toList();
        // booked a month ago: the rescheduled request is past its hold
        QuarkusTransaction
            .requiringNew()
            .run(() -> Booking.update("createdAt = ?1 where id in ?2", Instant.now().minus(30, ChronoUnit.DAYS), ids));
        clearInvocations(emailService);

        scheduler.expirePendingBookings();

        assertThat(statuses(ids)).containsExactly(
                BookingStatus.CONFIRMED,
                BookingStatus.DECLINED,
                BookingStatus.CONFIRMED
        );
        verify(emailService, times(1)).enqueueDeclined(anyLong(), anyList());
        verify(emailService).enqueueDeclined(ids.get(1), List.of(ids.get(1)));
        var kept = List.of(ids.getFirst(), ids.getLast());
        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> kept
                .stream()
                .map(id -> Booking.<Booking>findById(id).eventId)
                .toList())
        )
            .containsOnly("evt");
        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> Reminder.count("bookingId in ?1 and sentAt is null", kept))
        )
            .isEqualTo(2);
    }
}
