package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingCancelled;
import site.asm0dey.calit.booking.events.BookingSetCancelled;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.notify.ChannelDelivery;
import site.asm0dey.calit.scheduler.Reminder;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * "Cancel all" on a Booking set: cancels the Bookings still held and in the future, deletes their Mirror events and
 * mails once per recipient. Seeds commit and results are read back in fresh transactions.
 */
@QuarkusTest
class SetCancelTest {
    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate MONDAY =
            LocalDate.now(AMS).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private static final String EMAIL = "sam@x.com";
    private static final String OWNER = "pasha@x.com";
    private static final String GUEST = "gina@x.com";
    @Inject
    BookingService bookingService;
    @Inject
    MockMailbox mailbox;
    @InjectMock
    CalendarPort calendarPort;
    @InjectMock
    CaptchaVerifier captchaVerifier;

    @ApplicationScoped
    static class EventLog {
        private static final List<Long> CANCELLED = new CopyOnWriteArrayList<>();
        private static final List<BookingSetCancelled> SET_CANCELLED = new CopyOnWriteArrayList<>();

        void onCancelled(@Observes BookingCancelled e) {
            CANCELLED.add(e.bookingId());
        }

        void onSetCancelled(@Observes BookingSetCancelled e) {
            SET_CANCELLED.add(e);
        }

        private static final List<String> CHANNEL_BODIES = new CopyOnWriteArrayList<>();
        static volatile CountDownLatch channelHit = new CountDownLatch(0);

        void onDelivered(@ObservesAsync ChannelDelivery d) {
            CHANNEL_BODIES.add(d.message().body());
            channelHit.countDown();
        }

        static List<Long> cancelled() {
            return CANCELLED;
        }

        static List<BookingSetCancelled> setCancelled() {
            return SET_CANCELLED;
        }

        static List<String> channelBodies() {
            return CHANNEL_BODIES;
        }
    }

    @BeforeEach
    void setUp() {
        EventLog.cancelled().clear();
        EventLog.setCancelled().clear();
        EventLog.channelBodies().clear();
        mailbox.clear();
        QuarkusTransaction.requiringNew().run(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MeetingType t = MultiHostFixtures.meetingType(1L, "set", 30);
            t.multiSlotMin = 2;
            t.multiSlotMax = 3;
            t.multiSlotSameDay = true;
        });
    }

    private void connected() {
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
    }

    private static Instant at(int hour) {
        return MONDAY.atTime(hour, 0).atZone(AMS).toInstant();
    }

    private List<Booking> bookSet(List<String> guests) {
        var picks = Stream
            .of(10, 11, 12)
            .map(h -> new SlotPick(at(h), 30))
            .toList();
        return bookingService.bookSet(1L, "set", picks, "Sam", EMAIL, Map.of(), "tok", null, "", "en", guests, null);
    }

    private static List<Booking> rows() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>list("inviteeEmail = ?1 order by startUtc", EMAIL));
    }

    private static String ics(Mail mail) {
        assertThat(mail.getAttachments()).hasSize(1);
        var data = Multi
            .createFrom()
            .publisher(mail.getAttachments().getFirst().getData())
            .collect()
            .asList()
            .await()
            .indefinitely();
        var bytes = new byte[data.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = data.get(i);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void cancelSetCancelsRemainingOnly() {
        connected();
        var rows = bookSet(List.of());
        bookingService.cancel(rows.getFirst().manageToken);
        EventLog.cancelled().clear();
        clearInvocations(calendarPort);

        List<Booking> cancelled = bookingService.cancelSet(rows.get(1).manageToken, false);

        assertThat(cancelled)
            .extracting(b -> b.id)
            .containsExactly(rows.get(1).id, rows.get(2).id);
        assertThat(rows())
            .extracting(b -> b.status)
            .containsOnly(BookingStatus.CANCELLED);
        assertThat(rows()).allSatisfy(b -> assertThat(b.eventId).isNull());
        verify(calendarPort, times(2)).deleteEvent(anyLong(), any(), any());
        assertThat(EventLog.cancelled()).isEmpty();
        assertThat(EventLog.setCancelled())
            .singleElement()
            .satisfies(e -> {
                assertThat(e.leadBookingId()).isEqualTo(rows.getFirst().id);
                assertThat(e.cancelledIds()).containsExactly(rows.get(1).id, rows.get(2).id);
                assertThat(e.byOwner()).isFalse();
            });
        long unsent = QuarkusTransaction
            .requiringNew()
            .call(() -> Reminder.count("sentAt is null"));
        assertThat(unsent).isZero();
    }

    @Test
    void cancelSetLeavesPastSessions() {
        var rows = bookSet(List.of());
        QuarkusTransaction.requiringNew().run(() -> {
            Booking first = Booking.findById(rows.getFirst().id);
            first.startUtc = Instant.now().minus(2, ChronoUnit.HOURS);
            first.endUtc = first.startUtc.plus(30, ChronoUnit.MINUTES);
        });

        bookingService.cancelSet(rows.getLast().manageToken, true);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsExactly(BookingStatus.CONFIRMED, BookingStatus.CANCELLED, BookingStatus.CANCELLED);
        assertThat(EventLog.setCancelled())
            .singleElement()
            .satisfies(e -> assertThat(e.byOwner()).isTrue());
    }

    @Test
    void cancelSetWithNothingHeldIsNoOp() {
        var rows = bookSet(List.of());
        rows.forEach(b -> bookingService.cancel(b.manageToken));
        mailbox.clear();

        assertThat(bookingService.cancelSet(rows.getFirst().manageToken, false)).isEmpty();

        assertThat(EventLog.setCancelled()).isEmpty();
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void cancelSetSendsOneMailListingCancelled() {
        var rows = bookSet(List.of(GUEST));
        bookingService.cancel(rows.getFirst().manageToken);
        mailbox.clear();

        bookingService.cancelSet(rows.get(1).manageToken, false);

        List<Mail> toInvitee = mailbox.getMailsSentTo(EMAIL);
        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER);
        assertThat(toInvitee).hasSize(1);
        assertThat(toOwner).hasSize(1);
        assertThat(List.of(toInvitee.getFirst(), toOwner.getFirst()))
            .extracting(Mail::getHtml)
            .allSatisfy(html -> assertThat(html)
                .contains("at 11:00", "at 12:00", "1 of 2", "2 of 2")
                .doesNotContain("at 10:00"));
        String ics = ics(toInvitee.getFirst());
        assertThat(count(ics, "BEGIN:VEVENT")).isEqualTo(2);
        assertThat(ics)
            .contains("METHOD:CANCEL", "UID:" + rows.get(1).manageToken, "UID:" + rows.get(2).manageToken)
            .doesNotContain("UID:" + rows.getFirst().manageToken);

        List<Mail> toGuest = mailbox.getMailsSentTo(GUEST);
        assertThat(toGuest).hasSize(1);
        assertThat(count(ics(toGuest.getFirst()), "BEGIN:VEVENT")).isEqualTo(2);
    }

    @Test
    void cancellingOneBookingKeepsSiblings() {
        var rows = bookSet(List.of());
        mailbox.clear();

        bookingService.cancel(rows.get(1).manageToken);

        assertThat(rows())
            .extracting(b -> b.status)
            .containsExactly(BookingStatus.CONFIRMED, BookingStatus.CANCELLED, BookingStatus.CONFIRMED);
        List<Mail> toInvitee = mailbox.getMailsSentTo(EMAIL);
        assertThat(toInvitee).hasSize(1);
        assertThat(toInvitee.getFirst().getHtml()).contains("at 11:00").doesNotContain("1 of ");
        assertThat(count(ics(toInvitee.getFirst()), "BEGIN:VEVENT")).isEqualTo(1);
        assertThat(EventLog.cancelled()).containsExactly(rows.get(1).id);
        assertThat(EventLog.setCancelled()).isEmpty();
    }

    @Test
    void cancelSetChannelNoticeNamesACancelledSession() throws InterruptedException {
        var rows = bookSet(List.of());
        bookingService.cancel(rows.getFirst().manageToken);
        // a closed port: the async send fails harmlessly; the delivery event is what we capture
        QuarkusTransaction
            .requiringNew()
            .run(() -> MultiHostFixtures.channel(1L, "ntfy+http://localhost:9/calit", "Stub"));
        EventLog.channelBodies().clear();
        EventLog.channelHit = new CountDownLatch(1);

        bookingService.cancelSet(rows.get(1).manageToken, false);

        assertThat(EventLog.channelHit.await(10, TimeUnit.SECONDS)).as("no channel delivery").isTrue();
        assertThat(EventLog.channelBodies())
            .singleElement()
            .satisfies(body -> assertThat(body).contains("11:00").doesNotContain("10:00"));
    }
}
