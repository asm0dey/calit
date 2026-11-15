package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingApproved;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.booking.events.BookingDeclined;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.scheduler.Reminder;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * A pending Booking set is approved and declined as one. Seeds commit and results are read back in fresh
 * transactions, as {@code approve}/{@code decline} run in their own.
 */
@QuarkusTest
class SetApprovalTest {
    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    // two days out at least, so every slot is past the 24h reminder lead
    private static final LocalDate MONDAY =
            LocalDate.now(AMS).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private static final String EMAIL = "sam@x.com";
    @Inject
    BookingService bookingService;
    @InjectMock
    CalendarPort calendarPort;
    @InjectMock
    CaptchaVerifier captchaVerifier;

    @Singleton
    static class EventLog {
        static final List<Long> APPROVED = new CopyOnWriteArrayList<>();
        static final List<Long> CONFIRMED = new CopyOnWriteArrayList<>();
        static final List<Long> DECLINED = new CopyOnWriteArrayList<>();

        void approved(@Observes BookingApproved e) {
            APPROVED.add(e.bookingId());
        }

        void confirmed(@Observes BookingConfirmed e) {
            CONFIRMED.add(e.bookingId());
        }

        void declined(@Observes BookingDeclined e) {
            DECLINED.add(e.bookingId());
        }
    }

    @BeforeEach
    void setUp() {
        EventLog.APPROVED.clear();
        EventLog.CONFIRMED.clear();
        EventLog.DECLINED.clear();
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(anyCreateEvent()).thenReturn(new CreatedEvent("evt", null, null, null));
    }

    private CreatedEvent anyCreateEvent() {
        return calendarPort.createEvent(
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
        );
    }

    private static Instant at(int hour) {
        return MONDAY.atTime(hour, 0).atZone(AMS).toInstant();
    }

    private static void singleHostType() {
        singleHostType(true);
    }

    private static void singleHostType(boolean approval) {
        QuarkusTransaction.requiringNew().run(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MeetingType t = MultiHostFixtures.meetingType(1L, "set", 30);
            t.requiresApproval = approval;
            t.multiSlotMin = 2;
            t.multiSlotMax = 3;
            t.multiSlotSameDay = true;
        });
    }

    /**
     * Admin (id 1) creates, "volodya" co-hosts; returns volodya's id.
     */
    private static long coHostedType() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            AppUser v = MultiHostFixtures.enabledUser("volodya");
            MultiHostFixtures.settings(v.id, "volodya");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
            MeetingType t = MultiHostFixtures.acceptedTwoHostType(1L, v.id, "set", 30, true);
            t.multiSlotMin = 2;
            t.multiSlotMax = 2;
            t.multiSlotSameDay = true;
            return v.id;
        });
    }

    private List<Booking> bookSet(int... hours) {
        var picks = Arrays
            .stream(hours)
            .mapToObj(h -> new SlotPick(at(h), 30))
            .toList();
        return bookingService.bookSet(1L, "set", picks, "Sam", EMAIL, Map.of(), "tok", null, "", "en", List.of(), null);
    }

    private static List<Booking> rows() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>list("inviteeEmail = ?1 order by startUtc, ownerId", EMAIL));
    }

    private static Long lead(List<Booking> rows) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.leadOfSet(rows.getFirst().setId, 1L).id);
    }

    /**
     * Booking ids that hold an unsent reminder, in start order.
     */
    private static List<Long> remindedRows() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> rows()
                .stream()
                .filter(b -> Reminder.count("bookingId = ?1 and sentAt is null", b.id) > 0)
                .map(b -> b.id)
                .toList());
    }

    @Test
    void autoConfirmedSetGetsReminderPerBooking() {
        singleHostType(false);

        bookSet(10, 11);

        assertThat(remindedRows()).containsExactlyElementsOf(rows().stream().map(b -> b.id).toList());
    }

    @Test
    void approvingOneRowConfirmsWholeSet() {
        singleHostType();
        bookSet(10, 11, 12);
        var rows = rows();

        bookingService.approve(rows.get(1).id);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CONFIRMED);
        verify(calendarPort, times(3))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(EventLog.APPROVED).containsExactly(lead(rows));
        assertThat(EventLog.CONFIRMED).isEmpty();
        assertThat(remindedRows()).containsExactlyElementsOf(rows.stream().map(b -> b.id).toList());
    }

    @Test
    void coHostApprovalWaitsForEveryHost() {
        long volodya = coHostedType();
        bookSet(10, 11);
        var rows = rows();
        assertThat(rows).hasSize(4);
        Long hostARow = rows
            .stream()
            .filter(b -> b.ownerId == 1L)
            .findFirst()
            .orElseThrow().id;
        Long hostBRow = rows
            .stream()
            .filter(b -> b.ownerId == volodya)
            .findFirst()
            .orElseThrow().id;

        bookingService.approve(hostARow);

        assertThat(rows())
            .allSatisfy(b -> assertThat(b.status)
                .isEqualTo(b.ownerId == 1L ? BookingStatus.CONFIRMED : BookingStatus.PENDING));
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(EventLog.CONFIRMED).isEmpty();
        assertThat(EventLog.APPROVED).isEmpty();

        bookingService.approve(hostBRow);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CONFIRMED);
        verify(calendarPort, times(2))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(EventLog.CONFIRMED).containsExactly(lead(rows));
        assertThat(EventLog.APPROVED).isEmpty();
        // one per Booking, on the creator's (group lead) row
        assertThat(remindedRows())
            .containsExactlyElementsOf(rows
                .stream()
                .filter(b -> b.ownerId == 1L)
                .map(b -> b.id)
                .toList());
    }

    @Test
    void decliningOneRowDeclinesWholeSet() {
        singleHostType();
        bookSet(10, 11, 12);
        var rows = rows();

        bookingService.decline(rows.getLast().id);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.DECLINED);
        assertThat(EventLog.DECLINED).containsExactly(lead(rows));
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }

    @Test
    void setBehaviourSurvivesTurningMultiSlotOff() {
        singleHostType();
        bookSet(10, 11);
        var rows = rows();
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.findBySlug(1L, "set");
            t.multiSlotMin = null;
            t.multiSlotMax = null;
        });

        bookingService.approve(rows.getFirst().id);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CONFIRMED);
        assertThat(EventLog.APPROVED).containsExactly(lead(rows));
    }
}
