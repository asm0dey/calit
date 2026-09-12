package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.email.BookingSnapshotLoader;
import site.asm0dey.calit.test.MultiHostFixtures;

/** Every kind must render a non-empty title and body in every supported locale. */
@QuarkusTest
class ChannelMessageRendererTest {

    @Inject
    ChannelMessageRenderer renderer;

    @Inject
    BookingSnapshotLoader snapshots;

    private BookingSnapshot snapshot() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "Owner");
            MeetingType type = MultiHostFixtures.meetingType(1L, "render-me", 30);
            var start = Instant.parse("2026-06-08T09:00:00Z");
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = type.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = "tok-render";
            b.createdAt = Instant.now();
            b.persist();
            return snapshots.read(b.id);
        });
    }

    private static HostNotification.Host host(Locale locale) {
        return new HostNotification.Host(1L, locale, ZoneId.of("Europe/Berlin"), "auto");
    }

    private static BookingGuest guest() {
        var g = new BookingGuest();
        g.email = "guest@example.com";
        return g;
    }

    @Test
    void everyKindRendersInEveryLocale() {
        BookingSnapshot s = snapshot();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("he"))) {
            HostNotification.Host h = host(locale);
            var old = Instant.parse("2026-06-07T09:00:00Z");
            List<HostNotification> all = List.of(
                    new HostNotification.Requested(s, h),
                    new HostNotification.Confirmed(s, h),
                    new HostNotification.Approved(s, h),
                    new HostNotification.Declined(s, h),
                    new HostNotification.Cancelled(s, h, true),
                    new HostNotification.Rescheduled(s, h, old, false),
                    new HostNotification.DetailsChanged(s, h, false),
                    new HostNotification.GuestDeclined(s, h, guest()),
                    new HostNotification.GuestRemoved(s, h, guest()),
                    new HostNotification.ReminderDue(s, h),
                    new HostNotification.ConsentRequested(s.meetingType(), h, "consent-token"));
            for (HostNotification n : all) {
                var msg = renderer.render(n);
                assertNotNull(msg.title(), n.kind() + " title in " + locale);
                assertFalse(msg.title().isBlank(), n.kind() + " title in " + locale);
                assertFalse(msg.body().isBlank(), n.kind() + " body in " + locale);
            }
        }
    }

    @Test
    void kindIsTheWireStatusString() {
        BookingSnapshot s = snapshot();
        assertEquals("BOOKING_REQUESTED", new HostNotification.Requested(s, host(Locale.ENGLISH)).kind());
        assertEquals("BOOKING_CANCELLED", new HostNotification.Cancelled(s, host(Locale.ENGLISH), true).kind());
        assertEquals(
                "HOST_CONSENT_REQUESTED",
                new HostNotification.ConsentRequested(s.meetingType(), host(Locale.ENGLISH), "t").kind());
    }

    @Test
    void rescheduledBodyNamesBothTimes() {
        BookingSnapshot s = snapshot();
        var msg = renderer.render(new HostNotification.Rescheduled(
                s, host(Locale.ENGLISH), Instant.parse("2026-06-07T09:00:00Z"), false));
        assertTrue(msg.body().contains("→"), "rescheduled body shows old → new: " + msg.body());
    }

    @Test
    void consentBodyCarriesTheAcceptLink() {
        BookingSnapshot s = snapshot();
        var msg = renderer.render(
                new HostNotification.ConsentRequested(s.meetingType(), host(Locale.ENGLISH), "abc-123"));
        assertTrue(msg.body().contains("/consent/abc-123"), msg.body());
    }
}
