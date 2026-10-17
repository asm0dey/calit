package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

/**
 * With no Google calendar, calit's own .ics is the guest's only calendar source: a cancellation must be
 * METHOD:CANCEL and every .ics must carry the booking's current SEQUENCE.
 */
@QuarkusTest
class InviteeIcsMethodTest {
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";
    @Inject
    EmailService emailService;
    @Inject
    MockMailbox mailbox;
    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        QuarkusTransaction.requiringNew().run(() -> {
            BookingGuest.deleteAll();
            Booking.deleteAll();
        });
    }

    private long seed(BookingStatus status, int icsSequence) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Discovery Call";
            t.slug = "ics-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.CUSTOM;
            t.locationDetail = "Room 1";
            t.persist();
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = status;
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.icsSequence = icsSequence;
            b.persist();
            return b.id;
        });
    }

    private String ics(String to) {
        List<Mail> mails = mailbox.getMailsSentTo(to);
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().getAttachments()).hasSize(1);
        var data = Multi
            .createFrom()
            .publisher(mails.getFirst().getAttachments().getFirst().getData())
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

    @Test
    void cancellationIsCancel() {
        long id = seed(BookingStatus.CANCELLED, 0);

        emailService.handleCancelled(new BookingCancelled(id, false));

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:CANCEL", "STATUS:CANCELLED").doesNotContain("METHOD:REQUEST");
    }

    @Test
    void cancelAfterRescheduleCarriesCurrentSequence() {
        long id = seed(BookingStatus.CANCELLED, 2);

        emailService.handleCancelled(new BookingCancelled(id, false));

        assertThat(ics(INVITEE_EMAIL)).contains("SEQUENCE:2", "METHOD:CANCEL");
    }

    @Test
    void declineIsCancel() {
        long id = seed(BookingStatus.DECLINED, 0);

        emailService.handleDeclined(new BookingDeclined(id));

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:CANCEL");
    }

    @Test
    void rescheduleRequestCarriesSequence() {
        long id = seed(BookingStatus.CONFIRMED, 1);

        emailService.handleRescheduled(new BookingRescheduled(id, Instant.parse("2026-06-07T09:00:00Z"), false));

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:REQUEST", "SEQUENCE:1");
    }

    @Test
    void ownerCopyOfCancellationIsCancel() {
        long id = seed(BookingStatus.CANCELLED, 0);

        emailService.handleCancelled(new BookingCancelled(id, false));

        assertThat(ics(OWNER_EMAIL)).contains("METHOD:CANCEL");
    }

    @Test
    void downloadIsRequestWithCurrentSequence() {
        long id = seed(BookingStatus.CONFIRMED, 3);

        var bytes = emailService.inviteeIcs(id).orElseThrow();

        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("METHOD:REQUEST", "SEQUENCE:3");
    }
}
