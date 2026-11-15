package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
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
    @Inject
    MockMailbox mailbox;

    @ApplicationScoped
    static class EventLog {
        private static final List<Long> APPROVED = new CopyOnWriteArrayList<>();
        private static final List<Long> CONFIRMED = new CopyOnWriteArrayList<>();
        private static final List<Long> DECLINED = new CopyOnWriteArrayList<>();

        void onApproved(@Observes BookingApproved e) {
            APPROVED.add(e.bookingId());
        }

        void onConfirmed(@Observes BookingConfirmed e) {
            CONFIRMED.add(e.bookingId());
        }

        void onDeclined(@Observes BookingDeclined e) {
            DECLINED.add(e.bookingId());
        }

        static List<Long> approved() {
            return APPROVED;
        }

        static List<Long> confirmed() {
            return CONFIRMED;
        }

        static List<Long> declined() {
            return DECLINED;
        }
    }

    @BeforeEach
    void setUp() {
        EventLog.approved().clear();
        EventLog.confirmed().clear();
        EventLog.declined().clear();
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
        assertThat(EventLog.approved()).containsExactly(lead(rows));
        assertThat(EventLog.confirmed()).isEmpty();
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
        assertThat(EventLog.confirmed()).isEmpty();
        assertThat(EventLog.approved()).isEmpty();

        bookingService.approve(hostBRow);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CONFIRMED);
        verify(calendarPort, times(2))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(EventLog.confirmed()).containsExactly(lead(rows));
        assertThat(EventLog.approved()).isEmpty();
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
        assertThat(EventLog.declined()).containsExactly(lead(rows));
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
        assertThat(EventLog.approved()).containsExactly(lead(rows));
    }

    private static List<BookingStatus> statuses(List<Booking> booked) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> booked
                .stream()
                .map(b -> Booking.<Booking>findById(b.id).status)
                .toList());
    }

    private static Booking reload(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>findById(id));
    }

    private void forgetSoFar() {
        clearInvocations(calendarPort);
        mailbox.clear();
        EventLog.approved().clear();
        EventLog.confirmed().clear();
        EventLog.declined().clear();
    }

    private void verifyCreatedEvents(int n) {
        verify(calendarPort, times(n))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }

    /**
     * The one mail the Invitee got since {@link #forgetSoFar}.
     */
    private String inviteeMail() {
        List<Mail> mails = mailbox.getMailsSentTo(EMAIL);
        assertThat(mails).hasSize(1);
        return mails.getFirst().getHtml();
    }

    /**
     * Books 10/11/12, approves the set, then the Invitee moves the 11:00 session to 14:00, which puts that one Booking
     * back to PENDING. Returns its id.
     */
    private Long rescheduledSessionOfApprovedSet() {
        singleHostType();
        bookSet(10, 11, 12);
        var rows = rows();
        bookingService.approve(rows.getFirst().id);
        bookingService.reschedule(rows.get(1).manageToken, at(14));
        assertThat(statuses(rows)).containsExactly(
                BookingStatus.CONFIRMED,
                BookingStatus.PENDING,
                BookingStatus.CONFIRMED
        );
        forgetSoFar();
        return rows.get(1).id;
    }

    @Test
    void approvingRescheduledSessionLeavesTheOthersAlone() {
        Long moved = rescheduledSessionOfApprovedSet();

        bookingService.approve(moved);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CONFIRMED);
        verifyCreatedEvents(1);
        assertThat(EventLog.approved()).containsExactly(moved);
        assertThat(inviteeMail()).contains("at 14:00").doesNotContain("at 10:00", "at 12:00", " of 3");
        assertThat(remindedRows()).hasSize(3);
    }

    @Test
    void decliningRescheduledSessionDeclinesOnlyIt() {
        Long moved = rescheduledSessionOfApprovedSet();

        bookingService.decline(moved);

        assertThat(rows())
            .allSatisfy(b -> assertThat(b.status)
                .isEqualTo(b.id.equals(moved) ? BookingStatus.DECLINED : BookingStatus.CONFIRMED));
        verify(calendarPort, never()).deleteEvent(anyLong(), any(), any());
        assertThat(EventLog.declined()).containsExactly(moved);
        assertThat(inviteeMail()).contains("at 14:00").doesNotContain("at 10:00", "at 12:00", " of 3");
        assertThat(remindedRows()).hasSize(2).doesNotContain(moved);
    }

    /**
     * Books a pending 10/11/12 set and has the Invitee cancel session {@code cancelled} on its own.
     */
    private List<Booking> pendingSetWithCancelled(int cancelled) {
        singleHostType();
        bookSet(10, 11, 12);
        var rows = rows();
        bookingService.cancel(rows.get(cancelled).manageToken);
        forgetSoFar();
        return rows;
    }

    private void assertConfirmedAllBut(List<Booking> rows, int cancelled, String... sessionTimes) {
        var expected = new ArrayList<>(Collections.nCopies(3, BookingStatus.CONFIRMED));
        expected.set(cancelled, BookingStatus.CANCELLED);
        assertThat(statuses(rows)).containsExactlyElementsOf(expected);
        verifyCreatedEvents(2);
        assertThat(reload(rows.get(cancelled).id).eventId).isNull();
        var confirmedIds = rows
            .stream()
            .map(b -> b.id)
            .filter(id -> !id.equals(rows.get(cancelled).id))
            .toList();
        assertThat(EventLog.approved()).containsExactly(confirmedIds.getFirst());
        assertThat(inviteeMail())
            .contains(sessionTimes)
            .contains("1 of 2", "2 of 2")
            .doesNotContain("at " + (10 + cancelled) + ":00");
    }

    @Test
    void approvingViaCancelledLeadConfirmsTheRest() {
        var rows = pendingSetWithCancelled(0);

        bookingService.approve(rows.getFirst().id);

        assertConfirmedAllBut(rows, 0, "at 11:00", "at 12:00");
    }

    @Test
    void approvingViaSiblingOfCancelledLeadConfirmsTheRest() {
        var rows = pendingSetWithCancelled(0);

        bookingService.approve(rows.getLast().id);

        assertConfirmedAllBut(rows, 0, "at 11:00", "at 12:00");
    }

    @Test
    void approvingSetWithCancelledSessionConfirmsTheRest() {
        var rows = pendingSetWithCancelled(1);

        bookingService.approve(rows.getFirst().id);

        assertConfirmedAllBut(rows, 1, "at 10:00", "at 12:00");
    }

    @Test
    void decliningSetWithCancelledSessionKeepsItCancelled() {
        var rows = pendingSetWithCancelled(1);

        bookingService.decline(rows.getFirst().id);

        assertThat(statuses(rows)).containsExactly(
                BookingStatus.DECLINED,
                BookingStatus.CANCELLED,
                BookingStatus.DECLINED
        );
        assertThat(inviteeMail()).contains("at 10:00", "at 12:00", "1 of 2").doesNotContain("at 11:00");
    }
}
