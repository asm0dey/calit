package site.asm0dey.calit.scheduler;

import module java.base;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.notify.NotificationDispatcher;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ReminderGraceWindowTest {
    @Inject
    EntityManager em;
    @Inject
    EmailService emailService;
    @Inject
    NotificationDispatcher channels;
    /**
     * Built here with a 120 s grace window, so the default app's 30 s needs no test profile.
     */
    ReminderScheduler scheduler;

    @BeforeEach
    void buildScheduler() {
        scheduler = new ReminderScheduler(em, emailService, channels, 1440, 120);
    }

    @Test
    void dispatchSendsRemindersDueWithinGraceWindow() {
        var bookingId = seedBooking();
        var // +60s, grace=120s -> due
        withinGrace = persistReminder(bookingId, Instant.now().plus(60, ChronoUnit.SECONDS));
        // +1h -> not due
        var beyondGrace = persistReminder(bookingId, Instant.now().plus(1, ChronoUnit.HOURS));

        scheduler.dispatchDueReminders();

        assertThat(reloadSentAt(withinGrace)).as("reminder due within the grace window must be marked sent").isNotNull();
        assertThat(reloadSentAt(beyondGrace)).as("reminder beyond the grace window must stay unsent").isNull();
    }

    private Long seedBooking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "grace-" + System.nanoTime();
            t.slug = "grace-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            var start = Instant.now().plus(200, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plusSeconds(1800);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private Long persistReminder(Long bookingId, Instant sendAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = sendAt;
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();
            return r.id;
        });
    }

    private Instant reloadSentAt(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> ((Reminder) Reminder.findById(id)).sentAt);
    }
}
