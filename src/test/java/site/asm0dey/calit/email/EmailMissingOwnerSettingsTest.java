package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

/**
 * A booking whose owner has no {@code owner_settings} row must not blow up the mail path.
 *
 * <p>{@code EmailService.read} used to do {@code ZoneId.of(owner.timezone)} straight off a possibly
 * null {@code owner}, so such a booking threw NPE. On the reminder path that NPE was swallowed by
 * {@code ReminderScheduler}'s deliberate per-booking catch — which marks the reminder sent and drops
 * the mail — so the invitee silently never got their reminder and only an ERROR line recorded it
 * (calit-sv6a).
 *
 * <p>{@code OwnerSettings.seed} now covers all five account-creation paths, so this state should be
 * unreachable for any new account; these tests pin the degradation for a row that got there some
 * other way.
 */
@QuarkusTest
class EmailMissingOwnerSettingsTest {

    private static final String INVITEE_EMAIL = "invitee-nosettings@example.com";

    @Inject
    EmailService emailService;

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void enqueueReminderSkipsCleanlyWhenTheOwnerHasNoSettingsRow() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var bookingId = seedBookingThenDropSettings();

        // The whole point: no NPE escapes. Before the guard this threw, and the scheduler's
        // catch-all turned it into a silently dropped reminder.
        assertDoesNotThrow(() -> QuarkusTransaction.requiringNew().run(() -> emailService.enqueueReminder(bookingId)));

        QuarkusTransaction.requiringNew()
                .run(() -> assertEquals(
                        0,
                        EmailOutbox.count("recipient", INVITEE_EMAIL),
                        "nothing is enqueued — there is no owner to address the copy to"));

        cleanup(bookingId);
    }

    @Test
    void anUnparseableStoredZoneDoesNotBreakTheMailPath() {
        // calit-4whp guards both write paths, but a row written before that guard existed can still
        // hold a zone the JDK cannot parse. read() must degrade, not throw — one bad row would
        // otherwise take out every mail for that owner.
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var bookingId = seedBooking("Not/AZone");

        assertDoesNotThrow(() -> QuarkusTransaction.requiringNew().run(() -> emailService.enqueueReminder(bookingId)));

        QuarkusTransaction.requiringNew()
                .run(() -> assertEquals(
                        1,
                        EmailOutbox.count("recipient", INVITEE_EMAIL),
                        "the reminder still goes out, coerced to UTC"));

        cleanup(bookingId);
    }

    private Long seedBookingThenDropSettings() {
        var id = seedBooking("Europe/Amsterdam");
        QuarkusTransaction.requiringNew().run(() -> OwnerSettings.delete("ownerId", 1L));
        return id;
    }

    private Long seedBooking(String timezone) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner-nosettings@example.com";
            s.timezone = timezone;
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "No Settings Call";
            t.slug = "nosettings-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = MeetingType.LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            var start = Instant.now().plus(500, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }

    private void cleanup(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox.delete("recipient", INVITEE_EMAIL);
            EmailOutbox.delete("recipient", "owner-nosettings@example.com");
            Booking.deleteById(bookingId);
        });
    }
}
