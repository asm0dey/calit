package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingInviteService;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.CaptchaVerifier;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.booking.SlotPick;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.booking.events.GuestDeclined;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * A Booking set mails once: one request/confirmation per recipient listing every Booking, one .ics with a VEVENT per
 * Booking, one invite per guest. Degraded mode (calendar disconnected), so calit attaches every .ics itself.
 */
@QuarkusTest
class EmailSetTest {
    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate MONDAY =
            LocalDate.now(AMS).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private static final String EMAIL = "sam@x.com";
    private static final String OWNER = "pasha@x.com";
    private static final String GUEST = "gina@x.com";
    @Inject
    BookingService bookingService;
    @Inject
    BookingInviteService invites;
    @Inject
    EmailService emailService;
    @Inject
    MockMailbox mailbox;
    @InjectMock
    CalendarPort calendarPort;
    @InjectMock
    CaptchaVerifier captchaVerifier;

    @Singleton
    static class EventLog {
        static final List<Long> GUEST_DECLINED = new CopyOnWriteArrayList<>();

        void guestDeclined(@Observes GuestDeclined e) {
            GUEST_DECLINED.add(e.guestId());
        }
    }

    @BeforeEach
    void setUp() {
        mailbox.clear();
        EventLog.GUEST_DECLINED.clear();
    }

    private static Instant at(int hour) {
        return MONDAY.atTime(hour, 0).atZone(AMS).toInstant();
    }

    private static long setType(boolean approval) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "pasha");
            MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
            MeetingType t = MultiHostFixtures.meetingType(1L, "set", 30);
            t.requiresApproval = approval;
            t.multiSlotMin = 2;
            t.multiSlotMax = 3;
            t.multiSlotSameDay = true;
            return t.id;
        });
    }

    private void bookSet(List<String> guests, String inviteToken) {
        var picks = Stream
            .of(10, 11, 12)
            .map(h -> new SlotPick(at(h), 30))
            .toList();
        bookingService.bookSet(1L, "set", picks, "Sam", EMAIL, Map.of(), "tok", null, "", "en", guests, inviteToken);
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
    void requestMailListsEverySession() {
        setType(true);

        bookSet(List.of(), null);

        List<Mail> toInvitee = mailbox.getMailsSentTo(EMAIL);
        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER);
        assertThat(toInvitee).hasSize(1);
        assertThat(toOwner).hasSize(1);
        assertThat(List.of(toInvitee.getFirst(), toOwner.getFirst()))
            .extracting(Mail::getHtml)
            .allSatisfy(html -> assertThat(html).contains("at 10:00", "at 11:00", "at 12:00", "1 of 3", "3 of 3"));
        String ics = ics(toInvitee.getFirst());
        assertThat(count(ics, "BEGIN:VEVENT")).isEqualTo(3);
        assertThat(count(ics, "METHOD:")).isEqualTo(1);
    }

    @Test
    void confirmationMailOncePerSet() {
        setType(false);

        bookSet(List.of(), null);

        List<Mail> toInvitee = mailbox.getMailsSentTo(EMAIL);
        assertThat(toInvitee).hasSize(1);
        assertThat(count(ics(toInvitee.getFirst()), "BEGIN:VEVENT")).isEqualTo(3);
    }

    @Test
    void guestGetsOneInviteForSet() {
        setType(false);

        bookSet(List.of(GUEST), null);

        List<Mail> toGuest = mailbox.getMailsSentTo(GUEST);
        assertThat(toGuest).hasSize(1);
        String html = toGuest.getFirst().getHtml();
        assertThat(count(html, "/guest/")).isEqualTo(1);
        assertThat(html).contains("/guest/" + leadGuestToken() + "/decline", "1 of 3", "3 of 3");
        assertThat(count(ics(toGuest.getFirst()), "BEGIN:VEVENT")).isEqualTo(3);
    }

    @Test
    void guestDeclineRemovesFromEverySession() {
        setType(false);
        bookSet(List.of(GUEST), null);

        bookingService.declineGuest(leadGuestToken());

        List<BookingGuest> rows =
                QuarkusTransaction
            .requiringNew()
            .call(() -> BookingGuest.<BookingGuest>list("lower(email) = ?1", GUEST));
        assertThat(rows)
            .hasSize(3)
            .extracting(g -> g.status)
            .containsOnly(GuestStatus.DECLINED);
        assertThat(EventLog.GUEST_DECLINED).hasSize(1);
    }

    @Test
    void inviteSetMailHasNoSlug() {
        long typeId = setType(false);
        var token = invites.issue(1L, typeId, Instant.now());

        bookSet(List.of(GUEST), token);

        assertThat(mailbox.getTotalMessagesSent()).isGreaterThan(0);
        assertThat(Stream
            .of(EMAIL, OWNER, GUEST)
            .flatMap(to -> mailbox.getMailsSentTo(to).stream())
            .map(Mail::getHtml)
            .toList()
        )
            .isNotEmpty()
            .allSatisfy(html -> assertThat(html).doesNotContain("/admin/set"));
    }

    @Test
    void guestDeclineAfterOneCancelDropsTheHeldSessions() {
        setType(false);
        bookSet(List.of(GUEST), null);
        List<Booking> rows = setRows();
        bookingService.cancel(rows.getFirst().manageToken);
        mailbox.clear();

        bookingService.declineGuest(leadGuestToken());

        List<Mail> toGuest = mailbox.getMailsSentTo(GUEST);
        assertThat(toGuest).hasSize(1);
        String ics = ics(toGuest.getFirst());
        assertThat(ics).contains("METHOD:CANCEL", "UID:" + rows.get(1).manageToken, "UID:" + rows.get(2).manageToken);
        assertThat(count(ics, "BEGIN:VEVENT")).isEqualTo(2);
    }

    @Test
    void meetSetListsEachSessionsOwnRoom() {
        setType(false);
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.find("slug", "set").firstResult();
            t.locationType = MeetingType.LocationType.GOOGLE_MEET;
        });
        bookSet(List.of(), null);
        List<Booking> rows = setRows();
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < rows.size(); i++) {
                Booking.<Booking>findById(rows.get(i).id).meetLink = "https://meet.google.com/room-" + i;
            }
        });
        mailbox.clear();

        emailService.handleConfirmed(new BookingConfirmed(rows.getFirst().id));

        String html = mailbox.getMailsSentTo(EMAIL).getFirst().getHtml();
        assertThat(html).contains("room-0", "room-1", "room-2");
        assertThat(count(html, "https://meet.google.com/room-0")).as("once in its session line's href and text").isEqualTo(
                2
        );
    }

    @Test
    void inviteeIcsDownloadHoldsEverySession() {
        setType(false);
        bookSet(List.of(), null);

        String ics = new String(emailService.inviteeIcs(setRows().getFirst().id).orElseThrow(), StandardCharsets.UTF_8);

        assertThat(count(ics, "BEGIN:VEVENT")).isEqualTo(3);
    }

    private static List<Booking> setRows() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.<Booking>list("inviteeEmail = ?1 order by startUtc", EMAIL));
    }

    private static String leadGuestToken() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking any = Booking.<Booking>find("inviteeEmail", EMAIL).firstResult();
            Booking lead = Booking.leadOfSet(any.setId, 1L);
            return BookingGuest.findInBooking(lead.id, GUEST).declineToken;
        });
    }
}
