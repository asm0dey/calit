package site.asm0dey.calit.notify;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.alexmond.notify4j.Message;
import org.alexmond.notify4j.Severity;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.email.BookingSnapshot;
import site.asm0dey.calit.i18n.AppMessageResolver;

/**
 * Renders a {@link HostNotification} into a notify4j {@link Message} in the recipient's own locale,
 * timezone and hour cycle. Severity stays {@link Severity#DEFAULT} throughout: a booking is not an
 * incident, and every channel with a native priority notion keeps its own default.
 */
@ApplicationScoped
// S6813: CDI field injection is the established pattern across this codebase's beans.
@SuppressWarnings("java:S6813")
public class ChannelMessageRenderer {

    final AppMessageResolver messages;

    final String baseUrl;

    @Inject
    public ChannelMessageRenderer(AppMessageResolver messages, @ConfigProperty(name = "app.base-url") String baseUrl) {
        this.messages = messages;
        this.baseUrl = baseUrl;
    }

    public Message render(HostNotification n) {
        Locale locale = n.recipient().locale();
        var m = messages.forLocale(locale);
        return switch (n) {
            case HostNotification.Requested r -> booking(r.b(), n, m.email_requested_subject(label(r.b())));
            case HostNotification.Confirmed r -> booking(r.b(), n, m.email_confirmed_subject(label(r.b())));
            case HostNotification.Approved r -> booking(r.b(), n, m.email_approved_subject(label(r.b())));
            case HostNotification.Declined r -> booking(r.b(), n, m.email_declined_subject(label(r.b())));
            case HostNotification.Cancelled r -> booking(r.b(), n, m.email_cancelled_subject(label(r.b())));
            case HostNotification.DetailsChanged r -> booking(r.b(), n, m.email_updated_subject(label(r.b())));
            case HostNotification.ReminderDue r -> booking(r.b(), n, m.email_reminder_subject(label(r.b())));
            case HostNotification.Rescheduled r ->
                Message.of(
                        m.email_rescheduled_subject(label(r.b())),
                        m.channel_body_rescheduled(
                                r.b().booking().inviteeName,
                                when(r.oldStartUtc(), n),
                                when(r.b().booking().startUtc, n)),
                        Severity.DEFAULT);
            case HostNotification.GuestDeclined r ->
                Message.of(
                        m.channel_guest_declined_title(label(r.b())),
                        m.channel_guest_body(r.guest().email, when(r.b().booking().startUtc, n)),
                        Severity.DEFAULT);
            case HostNotification.GuestRemoved r ->
                Message.of(
                        m.channel_guest_removed_title(label(r.b())),
                        m.channel_guest_body(r.guest().email, when(r.b().booking().startUtc, n)),
                        Severity.DEFAULT);
            case HostNotification.ConsentRequested r ->
                Message.of(
                        m.channel_consent_title(),
                        m.channel_consent_body(r.meetingType().name, baseUrl + "/consent/" + r.consentToken()),
                        Severity.DEFAULT);
        };
    }

    /** The "Send test" message an owner triggers from /me/settings. */
    public Message test(Locale locale) {
        var m = messages.forLocale(locale);
        return Message.of(m.channel_test_title(), m.channel_test_body(), Severity.DEFAULT);
    }

    private Message booking(BookingSnapshot b, HostNotification n, String title) {
        var m = messages.forLocale(n.recipient().locale());
        return Message.of(
                title, m.channel_body(b.booking().inviteeName, when(b.booking().startUtc, n)), Severity.DEFAULT);
    }

    /** The meeting label shown in every message: the booking's title override, else the type name. */
    private static String label(BookingSnapshot b) {
        return b.booking().effectiveTitle(b.meetingType());
    }

    /** Same pattern keys the emails use, so a host sees one house style across email and channels. */
    private String when(Instant instant, HostNotification n) {
        Locale locale = n.recipient().locale();
        var m = messages.forLocale(locale);
        String pattern =
                "h12".equals(n.recipient().hourCycle()) ? m.email_datetime_pattern_h12() : m.email_datetime_pattern();
        return DateTimeFormatter.ofPattern(pattern, locale)
                .format(instant.atZone(n.recipient().zone()));
    }
}
