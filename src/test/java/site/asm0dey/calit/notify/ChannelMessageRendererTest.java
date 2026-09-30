package site.asm0dey.calit.notify;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.email.BookingSnapshotLoader;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * Every kind must render a non-empty title and body in every supported locale.
 */
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
                    new HostNotification.ConsentRequested(s.meetingType(), h, "consent-token")
            );
            for (HostNotification n : all) {
                var msg = renderer.render(n);
                assertThat(msg.title()).as(n.kind() + " title in " + locale).isNotNull();
                assertThat(msg.title().isBlank()).as(n.kind() + " title in " + locale).isFalse();
                assertThat(msg.body().isBlank()).as(n.kind() + " body in " + locale).isFalse();
            }
        }
    }

    @Test
    void kindIsTheWireStatusString() {
        BookingSnapshot s = snapshot();
        assertThat(new HostNotification.Requested(s, host(Locale.ENGLISH)).kind()).isEqualTo("BOOKING_REQUESTED");
        assertThat(new HostNotification.Cancelled(s, host(Locale.ENGLISH), true).kind()).isEqualTo("BOOKING_CANCELLED");
        assertThat(new HostNotification.ConsentRequested(s.meetingType(), host(Locale.ENGLISH), "t").kind())
            .isEqualTo("HOST_CONSENT_REQUESTED");
    }

    @Test
    void rescheduledBodyNamesBothTimes() {
        BookingSnapshot s = snapshot();
        var msg = renderer.render(
                new HostNotification.Rescheduled(s, host(Locale.ENGLISH), Instant.parse("2026-06-07T09:00:00Z"), false)
        );
        assertThat(msg.body()).as("rescheduled body shows old → new: " + msg.body()).contains("→");
    }

    @Test
    void consentBodyCarriesTheAcceptLink() {
        BookingSnapshot s = snapshot();
        var msg =
                renderer.render(new HostNotification.ConsentRequested(s.meetingType(), host(Locale.ENGLISH), "abc-123"));
        assertThat(msg.body()).as(msg.body()).contains("/consent/abc-123");
    }
}
