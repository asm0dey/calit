package site.asm0dey.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.i18n.AppLocales;
import site.asm0dey.calit.i18n.AppMessageResolver;
import site.asm0dey.calit.i18n.AppMessages;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

@ApplicationScoped
public class EmailService {

    public static final String RECIPIENT_ROLE = "recipientRole";
    public static final String RECIPIENT_ROLE_DISPLAY = "recipientRoleDisplay";
    /** Recipient-role values passed to the per-role body builder. */
    private static final String INVITEE_ROLE = "invitee";
    private static final String OWNER_ROLE = "owner";
    public static final String INVITEE_NAME = "inviteeName";
    public static final String MEETING_TYPE_NAME = "meetingTypeName";
    public static final String START_TIME = "startTime";
    public static final String DURATION_MINUTES = "durationMinutes";
    public static final String LOCATION = "location";
    public static final String IS_MEET_LINK = "isMeetLink";
    public static final String MANAGE_URL = "manageUrl";
    public static final String ANSWERS = "answers";

    @Inject
    MailSender mailSender;

    @Inject
    AppMessageResolver messages;

    @Inject
    CalendarPort calendarPort;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    @Inject
    @Location("email/requested.html")
    Template requested;

    @Inject
    @Location("email/confirmation.html")
    Template confirmation;

    @Inject
    @Location("email/declined.html")
    Template declined;

    @Inject
    @Location("email/reschedule.html")
    Template reschedule;

    @Inject
    @Location("email/cancellation.html")
    Template cancellation;

    @Inject
    @Location("email/reminder.html")
    Template reminder;

    @Inject
    @Location("email/password-reset.html")
    Template passwordReset;

    @Inject
    @Location("email/google-disconnected.html")
    Template googleDisconnected;

    /**
     * Sends a password-reset link. Caller has already resolved the destination address.
     * {@code expiresAt} is the reset token's expiry: if the mail can't be sent now and has to fall
     * back to the outbox, retries stop at that instant so a dead-link email is never delivered.
     * {@code locale} drives any {msg:} keys rendered in the template body.
     */
    public void sendPasswordReset(String toEmail, String resetUrl, Instant expiresAt, Locale locale) {
        String body = passwordReset.instance().setLocale(locale)
                .data("lang", locale.getLanguage())
                .data("resetUrl", resetUrl).render();
        mailSender.send(toEmail, messages.forLocale(locale).email_password_reset_subject(), body, null, expiresAt);
    }

    /**
     * Critical operational alert: the owner's Google account is disconnected and their booking page
     * is paused. Sent regardless of {@code ownerNotificationsEnabled} (that flag governs only routine
     * booking notifications). Links to the Google settings page so the owner can reconnect.
     * {@code locale} drives any {msg:} keys rendered in the template body.
     */
    public void sendGoogleDisconnected(String toEmail, String accountEmail, Locale locale) {
        String reconnectUrl = baseUrl + "/me/google";
        String body = googleDisconnected.instance().setLocale(locale)
                .data("lang", locale.getLanguage())
                .data("accountEmail", accountEmail == null ? "your account" : accountEmail)
                .data("reconnectUrl", reconnectUrl)
                .render();
        mailSender.send(toEmail, messages.forLocale(locale).email_google_disconnected_subject(), body, null);
    }

    /** Which invitee-delivery rule a kind follows. */
    private enum InviteeRule {
        /** Always send to invitee (no Google event exists for this state). */
        ALWAYS,
        /** Send to invitee only when Google is NOT connected (Google notifies otherwise). */
        FALLBACK
    }

    /** Where a rendered mail goes: either a direct SMTP send or an outbox enqueue. */
    @FunctionalInterface
    private interface MailSink {
        void deliver(String to, String subject, String html, byte[] ics);
    }

    /**
     * In-transaction sink: persist the rendered mail to the outbox (a fast INSERT, no SMTP) so it
     * commits atomically with the caller's transaction. OutboxScheduler delivers it with retry/backoff.
     * Static so it can be used as a method reference with no captured state.
     */
    private static void enqueueToOutbox(String to, String subject, String html, byte[] ics) {
        EmailOutbox.enqueue(to, subject, html, ics, null, "scheduled dispatch (transactional outbox)");
    }

    // --- CDI observers: fire only after the booking transaction commits. ---

    void onRequested(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRequested e) {
        handleRequested(e);
    }

    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingConfirmed e) {
        handleConfirmed(e);
    }

    void onApproved(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingApproved e) {
        handleApproved(e);
    }

    void onDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDeclined e) {
        handleDeclined(e);
    }

    void onRescheduled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRescheduled e) {
        handleRescheduled(e);
    }

    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        handleCancelled(e);
    }

    void onReminder(@Observes(during = TransactionPhase.AFTER_SUCCESS) ReminderDue e) {
        handleReminder(e);
    }

    // --- Package-private helpers: own their transaction, directly unit-testable. ---

    void handleRequested(BookingRequested e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        sendForKindLocaleAware(InviteeRule.ALWAYS, l, location,
                messages.forLocale(inviteeLocale).email_requested_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_requested_subject(l.meetingType.name),
                role -> {
                    Locale locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    String start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return requested.instance().setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                });
    }

    void handleConfirmed(BookingConfirmed e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        sendForKindLocaleAware(InviteeRule.FALLBACK, l, location,
                messages.forLocale(inviteeLocale).email_confirmed_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_confirmed_subject(l.meetingType.name),
                role -> {
                    Locale locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    String start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return confirmation.instance().setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                });
    }

    void handleApproved(BookingApproved e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        // Same body as confirmed (now confirmed after approval); only subject differs.
        sendForKindLocaleAware(InviteeRule.FALLBACK, l, location,
                messages.forLocale(inviteeLocale).email_approved_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_approved_subject(l.meetingType.name),
                role -> {
                    Locale locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    String start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return confirmation.instance().setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                });
    }

    void handleDeclined(BookingDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverDeclined(l, mailSender::send);
    }

    /** Renders + delivers the declined email through the given sink (direct or outbox). */
    private void deliverDeclined(Loaded l, MailSink sink) {
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        // No Google event ever existed -> always notify the invitee. No answers, no location link.
        sendForKindLocaleAware(InviteeRule.ALWAYS, l, resolveLocation(l),
                messages.forLocale(inviteeLocale).email_declined_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_declined_subject(l.meetingType.name),
                role -> {
                    Locale locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    String start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return declined.instance().setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .render();
                },
                sink);
    }

    /** Renders the declined email and enqueues it in the CALLER's transaction (atomic with the claim). */
    public void enqueueDeclined(Long bookingId) {
        Loaded l = read(bookingId);
        if (l == null) return;
        deliverDeclined(l, EmailService::enqueueToOutbox);
    }

    void handleRescheduled(BookingRescheduled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeNewStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerNewStart = format(l.booking.startUtc, l.zone, ownerLocale);
        String inviteeOldStart = format(e.oldStartUtc(), l.zone, inviteeLocale);
        String ownerOldStart = format(e.oldStartUtc(), l.zone, ownerLocale);
        sendForKindLocaleAware(InviteeRule.FALLBACK, l, location,
                messages.forLocale(inviteeLocale).email_rescheduled_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_rescheduled_subject(l.meetingType.name),
                role -> {
                    Locale locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    String newStart = INVITEE_ROLE.equals(role) ? inviteeNewStart : ownerNewStart;
                    String oldStart = INVITEE_ROLE.equals(role) ? inviteeOldStart : ownerOldStart;
                    return reschedule.instance().setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, newStart)
                            .data("oldStartTime", oldStart)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                });
    }

    void handleCancelled(BookingCancelled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        // No location/meet link in the cancellation body; .ics still attached describing the removed event.
        sendForKindLocaleAware(InviteeRule.FALLBACK, l, resolveLocation(l),
                messages.forLocale(inviteeLocale).email_cancelled_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_cancelled_subject(l.meetingType.name),
                role -> {
                    Locale locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    String start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return cancellation.instance().setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .render();
                });
    }

    void handleReminder(ReminderDue e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverReminder(l, mailSender::send);
    }

    /** Renders + delivers the reminder email through the given sink (direct or outbox). */
    private void deliverReminder(Loaded l, MailSink sink) {
        String location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        sendForKindLocaleAware(InviteeRule.FALLBACK, l, location,
                messages.forLocale(inviteeLocale).email_reminder_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_reminder_subject(l.meetingType.name),
                role -> {
                    Locale locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    String start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return reminder.instance().setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                },
                sink);
    }

    /** Renders the reminder email and enqueues it in the CALLER's transaction (atomic with the claim). */
    public void enqueueReminder(Long bookingId) {
        Loaded l = read(bookingId);
        if (l == null) return;
        deliverReminder(l, EmailService::enqueueToOutbox);
    }

    // --- recipient selection + send plumbing ---

    /**
     * Locale-aware delivery (default SMTP sink). Invitee and owner may get different subjects and
     * body renderings according to their respective locales.
     */
    private void sendForKindLocaleAware(InviteeRule rule, Loaded l, String icsLocation,
                                        String inviteeSubject, String ownerSubject,
                                        UnaryOperator<String> bodyForRole) {
        sendForKindLocaleAware(rule, l, icsLocation,
                inviteeSubject, ownerSubject,
                bodyForRole, mailSender::send);
    }

    /**
     * Renders the body (per recipient role) and delivers it through {@code sink} to the selected
     * recipients, each with the .ics, using per-recipient locale for subject and body.
     * Owner included iff {@code ownerNotificationsEnabled}; invitee per {@code rule} and
     * {@code calendarPort.isConnected()}. No mail if the recipient set is empty.
     */
    private void sendForKindLocaleAware(InviteeRule rule, Loaded l, String icsLocation,
                                        String inviteeSubject, String ownerSubject,
                                        UnaryOperator<String> bodyForRole, MailSink sink) {
        boolean sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected(l.owner.ownerId);
        boolean sendOwner = l.owner.ownerNotificationsEnabled;

        byte[] ics = IcsBuilder.build(l.booking.manageToken, l.meetingType.name, icsLocation,
                l.owner.ownerEmail, l.owner.ownerName,
                l.booking.inviteeEmail, l.booking.inviteeName,
                l.booking.startUtc, l.booking.endUtc)
                .getBytes(StandardCharsets.UTF_8);

        if (sendInvitee) {
            sink.deliver(l.booking.inviteeEmail, inviteeSubject, bodyForRole.apply(INVITEE_ROLE), ics);
        }
        if (sendOwner) {
            sink.deliver(l.owner.ownerEmail, ownerSubject, bodyForRole.apply(OWNER_ROLE), ics);
        }
    }

    /** Returns the localized display word for the recipient role in the email footer. */
    private String localizedRole(String role, Locale locale) {
        AppMessages m = messages.forLocale(locale);
        return INVITEE_ROLE.equals(role) ? m.email_role_invitee() : m.email_role_owner();
    }

    private String manageUrl(Booking b) {
        return baseUrl + "/booking/" + b.manageToken + "/manage";
    }

    /** Meet link for GOOGLE_MEET types, else the type's locationDetail (phone/address/custom). */
    private static String resolveLocation(Loaded l) {
        if (l.meetingType.locationType == LocationType.GOOGLE_MEET) {
            return l.booking.meetLink; // may be null when Google is disconnected
        }
        return l.meetingType.locationDetail;
    }

    private static boolean isMeet(Loaded l) {
        return l.meetingType.locationType == LocationType.GOOGLE_MEET;
    }

    private String format(Instant instant, ZoneId zone, Locale locale) {
        String pattern = messages.forLocale(locale).email_datetime_pattern();
        return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone));
    }

    /**
     * Loads the booking + meeting type + owner settings + answers in the CALLER's active transaction.
     * Use from an already-transactional caller (the scheduler claim tx). Returns null if gone.
     */
    private Loaded read(Long bookingId) {
        Booking booking = Booking.findById(bookingId);
        if (booking == null) {
            return null;
        }
        MeetingType type = MeetingType.findById(booking.meetingTypeId);
        OwnerSettings owner = OwnerSettings.forOwner(type.ownerId);
        ZoneId zone = ZoneId.of(owner.timezone);
        List<AnswerLine> answers = buildAnswerLines(booking, type);
        return new Loaded(booking, type, owner, zone, answers);
    }

    /** As {@link #read} but opens its own transaction — for AFTER_SUCCESS observers (no active tx). */
    private Loaded load(Long bookingId) {
        return QuarkusTransaction.requiringNew().call(() -> read(bookingId));
    }

    /**
     * Joins {@code BookingField.formFor(meetingTypeId)} (ordered by {@code position}) to
     * {@code booking.answers} by {@code fieldKey}, skipping blank/absent values. Must run inside a
     * transaction -- the {@code requiringNew()} one opened by {@link #load} (event path) or the
     * caller's active transaction via {@link #read} (scheduler enqueue path).
     */
    private static List<AnswerLine> buildAnswerLines(Booking booking, MeetingType type) {
        List<AnswerLine> lines = new ArrayList<>();
        Map<String, String> answers = booking.answers;
        if (answers == null || answers.isEmpty()) {
            return lines;
        }
        for (BookingField field : BookingField.formFor(type.ownerId, booking.meetingTypeId)) {
            String value = answers.get(field.fieldKey);
            if (value != null && !value.isBlank()) {
                lines.add(new AnswerLine(field.label, value));
            }
        }
        return lines;
    }

    /** Immutable bundle read once in one transaction. */
    private record Loaded(Booking booking, MeetingType meetingType, OwnerSettings owner, ZoneId zone,
                          List<AnswerLine> answers) {}

    /** One rendered custom-field answer: human label + submitted value. Public for Qute access. */
    public record AnswerLine(String label, String value) {}
}
