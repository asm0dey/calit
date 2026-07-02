package site.asm0dey.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.i18n.AppLocales;
import site.asm0dey.calit.i18n.AppMessageResolver;

@ApplicationScoped
// S6813: CDI field injection is the established pattern across this codebase's beans.
@SuppressWarnings("java:S6813")
public class EmailService {

    public static final String RECIPIENT_ROLE = "recipientRole";
    /** Recipient-role values passed to the per-role body builder. */
    private static final String INVITEE_ROLE = "invitee";

    private static final String OWNER_ROLE = "owner";
    private static final String GUEST_ROLE = "guest";
    public static final String DECLINE_GUEST_URL = "declineGuestUrl";
    public static final String GUEST_EMAIL_DATA = "guestEmail";
    public static final String INVITEE_NAME = "inviteeName";
    public static final String OWNER_NAME = "ownerName";
    public static final String MEETING_TYPE_NAME = "meetingTypeName";
    public static final String DESCRIPTION = "description";
    public static final String START_TIME = "startTime";
    public static final String DURATION_MINUTES = "durationMinutes";
    public static final String LOCATION = "location";
    public static final String IS_MEET_LINK = "isMeetLink";
    public static final String MANAGE_URL = "manageUrl";
    /** Owner-only login-gated manage (reschedule/cancel) link on /me; rendered only for the owner copy. */
    public static final String OWNER_MANAGE_URL = "ownerManageUrl";

    public static final String ANSWERS = "answers";

    /** Whether the host (vs. the invitee) drove the change — flips reschedule/cancel/update wording. */
    public static final String BY_OWNER = "byOwner";

    /** Role-aware greeting name: invitee name for the invitee copy, owner name for the owner copy. */
    public static final String GREETING_NAME = "greetingName";
    /** Owner-only authenticated approve/decline links (requested email); null for the invitee copy. */
    public static final String APPROVE_URL = "approveUrl";

    public static final String DECLINE_URL = "declineUrl";
    /** Invitee cancel-confirmation link. */
    public static final String CANCEL_URL = "cancelUrl";

    @Inject
    MailSender mailSender;

    @Inject
    AppMessageResolver messages;

    @Inject
    CalendarPort calendarPort;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    /**
     * The address every mail is actually sent From. Gmail refuses to render an iTIP REQUEST whose
     * ORGANIZER differs from the message sender ("Unable to load event"), so the .ics ORGANIZER must
     * use this address; the owner's real name is kept as the ORGANIZER CN. (Gmail ignores SENT-BY.)
     */
    @ConfigProperty(name = "app.mail-from")
    String mailFrom;

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
        String body = passwordReset
                .instance()
                .setLocale(locale)
                .data("lang", locale.getLanguage())
                .data("resetUrl", resetUrl)
                .render();
        mailSender.send(
                null, toEmail, messages.forLocale(locale).email_password_reset_subject(), body, null, expiresAt);
    }

    /**
     * Critical operational alert: the owner's Google account is disconnected and their booking page
     * is paused. Sent regardless of {@code ownerNotificationsEnabled} (that flag governs only routine
     * booking notifications). Links to the Google settings page so the owner can reconnect.
     * {@code locale} drives any {msg:} keys rendered in the template body.
     */
    public void sendGoogleDisconnected(String toEmail, String accountEmail, Locale locale) {
        var reconnectUrl = baseUrl + "/me/google";
        String body = googleDisconnected
                .instance()
                .setLocale(locale)
                .data("lang", locale.getLanguage())
                .data("accountEmail", accountEmail == null ? "your account" : accountEmail)
                .data("reconnectUrl", reconnectUrl)
                .render();
        mailSender.send(null, toEmail, messages.forLocale(locale).email_google_disconnected_subject(), body, null);
    }

    // basePath = "email": @Location on individual @CheckedTemplate native methods is NOT honored by
    // Qute's build-time processor (only @CheckedTemplate.basePath()/defaultName() drive path
    // resolution) -- confirmed by inspecting QuteProcessor#collectCheckedTemplates, which never reads
    // a @Location annotation off the method target. Without basePath the method would resolve to
    // EmailService/reminder instead of email/reminder.html.
    @CheckedTemplate(basePath = "email")
    static class Templates {
        static native TemplateInstance reminder(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);

        static native TemplateInstance requested(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String cancelUrl,
                String approveUrl,
                String declineUrl,
                List<AnswerLine> answers);

        static native TemplateInstance confirmation(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);

        static native TemplateInstance declined(
                String recipientRole,
                String lang,
                String greetingName,
                String inviteeName,
                String meetingTypeName,
                String startTime,
                int durationMinutes);

        static native TemplateInstance reschedule(
                String recipientRole,
                boolean byOwner,
                String lang,
                String inviteeName,
                String ownerName,
                String greetingName,
                String meetingTypeName,
                String startTime,
                String oldStartTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);

        static native TemplateInstance updated(
                String recipientRole,
                boolean byOwner,
                String description,
                String lang,
                String inviteeName,
                String ownerName,
                String greetingName,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String manageUrl,
                String ownerManageUrl,
                String cancelUrl,
                List<AnswerLine> answers);

        static native TemplateInstance cancellation(
                String recipientRole,
                boolean byOwner,
                String lang,
                String inviteeName,
                String ownerName,
                String greetingName,
                String meetingTypeName,
                String startTime,
                int durationMinutes);

        static native TemplateInstance guestInvite(
                String lang,
                String greetingName,
                String inviteeName,
                String meetingTypeName,
                String startTime,
                int durationMinutes,
                String location,
                boolean isMeetLink,
                String declineGuestUrl);

        static native TemplateInstance guestCancel(
                String lang, String greetingName, String meetingTypeName, String startTime);

        static native TemplateInstance guestDeclinedNotice(
                String lang,
                String greetingName,
                String guestEmail,
                String meetingTypeName,
                String startTime,
                String manageUrl);
    }

    /** Where a rendered mail goes: either a direct SMTP send or an outbox enqueue. */
    @FunctionalInterface
    private interface MailSink {
        void deliver(String fromName, String to, String subject, String html, byte[] ics);
    }

    /**
     * In-transaction sink: persist the rendered mail to the outbox (a fast INSERT, no SMTP) so it
     * commits atomically with the caller's transaction. OutboxScheduler delivers it with retry/backoff.
     * Static so it can be used as a method reference with no captured state.
     */
    private static void enqueueToOutbox(String fromName, String to, String subject, String html, byte[] ics) {
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

    void onDetailsChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDetailsChanged e) {
        handleDetailsChanged(e);
    }

    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        handleCancelled(e);
    }

    void onGuestDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestDeclined e) {
        handleGuestDeclined(e);
    }

    void onGuestRemoved(@Observes(during = TransactionPhase.AFTER_SUCCESS) GuestRemoved e) {
        handleGuestRemoved(e);
    }

    void onReminder(@Observes(during = TransactionPhase.AFTER_SUCCESS) ReminderDue e) {
        handleReminder(e);
    }

    // --- Package-private helpers: own their transaction, directly unit-testable. ---

    void handleRequested(BookingRequested e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        sendForKindLocaleAware(
                l,
                location,
                messages.forLocale(inviteeLocale).email_requested_subject(label(l)),
                messages.forLocale(ownerLocale).email_requested_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return Templates.requested(
                                    role,
                                    locale.getLanguage(),
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    l.booking.inviteeName,
                                    label(l),
                                    start,
                                    l.meetingType.durationMinutes,
                                    location,
                                    isMeet(l),
                                    manageUrl(l.booking),
                                    cancelUrl(l.booking),
                                    approveUrl(l.booking),
                                    declineUrl(l.booking),
                                    l.answers)
                            .setLocale(locale)
                            .render();
                });
    }

    void handleConfirmed(BookingConfirmed e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        sendForKindLocaleAware(
                l,
                location,
                messages.forLocale(inviteeLocale).email_confirmed_subject(label(l)),
                messages.forLocale(ownerLocale).email_confirmed_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return Templates.confirmation(
                                    role,
                                    locale.getLanguage(),
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    l.booking.inviteeName,
                                    label(l),
                                    start,
                                    l.meetingType.durationMinutes,
                                    location,
                                    isMeet(l),
                                    manageUrl(l.booking),
                                    ownerManageUrl(l.booking),
                                    cancelUrl(l.booking),
                                    l.answers)
                            .setLocale(locale)
                            .render();
                });
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_confirmed_subject(label(l)));
    }

    void handleApproved(BookingApproved e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        // Same body as confirmed (now confirmed after approval); only subject differs.
        sendForKindLocaleAware(
                l,
                location,
                messages.forLocale(inviteeLocale).email_approved_subject(label(l)),
                messages.forLocale(ownerLocale).email_approved_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return Templates.confirmation(
                                    role,
                                    locale.getLanguage(),
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    l.booking.inviteeName,
                                    label(l),
                                    start,
                                    l.meetingType.durationMinutes,
                                    location,
                                    isMeet(l),
                                    manageUrl(l.booking),
                                    ownerManageUrl(l.booking),
                                    cancelUrl(l.booking),
                                    l.answers)
                            .setLocale(locale)
                            .render();
                });
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_confirmed_subject(label(l)));
    }

    void handleDeclined(BookingDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverDeclined(l, mailSender::send);
        // Guests of an approval booking that was confirmed/approved then rescheduled back to PENDING
        // (icsSequence>0) hold a stale calendar event; a now-declined re-approval must cancel it for
        // them. A never-confirmed PENDING booking (icsSequence==0) never sent guest invites -> no cancel.
        if (l.booking.icsSequence > 0) {
            sendGuestCancels(
                    l, messages.forLocale(AppLocales.pick(l.booking.locale)).email_cancelled_subject(label(l)));
        }
    }

    /** Renders + delivers the declined email through the given sink (direct or outbox). */
    private void deliverDeclined(Loaded l, MailSink sink) {
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        // No Google event ever existed -> always notify the invitee. No answers, no location link.
        sendForKindLocaleAware(
                l,
                resolveLocation(l),
                messages.forLocale(inviteeLocale).email_declined_subject(label(l)),
                messages.forLocale(ownerLocale).email_declined_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return Templates.declined(
                                    role,
                                    locale.getLanguage(),
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    l.booking.inviteeName,
                                    label(l),
                                    start,
                                    l.meetingType.durationMinutes)
                            .setLocale(locale)
                            .render();
                },
                sink);
    }

    /** Renders the declined email and enqueues it in the CALLER's transaction (atomic with the claim). */
    public void enqueueDeclined(Long bookingId) {
        var l = read(bookingId);
        if (l == null) return;
        deliverDeclined(l, EmailService::enqueueToOutbox);
    }

    void handleRescheduled(BookingRescheduled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeNewStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerNewStart = format(l.booking.startUtc, l.zone, ownerLocale);
        String inviteeOldStart = format(e.oldStartUtc(), l.zone, inviteeLocale);
        String ownerOldStart = format(e.oldStartUtc(), l.zone, ownerLocale);
        sendForKindLocaleAware(
                l,
                location,
                messages.forLocale(inviteeLocale).email_rescheduled_subject(label(l)),
                messages.forLocale(ownerLocale).email_rescheduled_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var newStart = INVITEE_ROLE.equals(role) ? inviteeNewStart : ownerNewStart;
                    var oldStart = INVITEE_ROLE.equals(role) ? inviteeOldStart : ownerOldStart;
                    return Templates.reschedule(
                                    role,
                                    e.byOwner(),
                                    locale.getLanguage(),
                                    l.booking.inviteeName,
                                    l.owner.ownerName,
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    label(l),
                                    newStart,
                                    oldStart,
                                    l.meetingType.durationMinutes,
                                    location,
                                    isMeet(l),
                                    manageUrl(l.booking),
                                    ownerManageUrl(l.booking),
                                    cancelUrl(l.booking),
                                    l.answers)
                            .setLocale(locale)
                            .render();
                });
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_rescheduled_subject(label(l)));
    }

    void handleDetailsChanged(BookingDetailsChanged e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        String desc = l.booking.effectiveDescription(l.meetingType);
        sendForKindLocaleAware(
                l,
                location,
                messages.forLocale(inviteeLocale).email_updated_subject(label(l)),
                messages.forLocale(ownerLocale).email_updated_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return Templates.updated(
                                    role,
                                    e.byOwner(),
                                    desc,
                                    locale.getLanguage(),
                                    l.booking.inviteeName,
                                    l.owner.ownerName,
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    label(l),
                                    start,
                                    l.meetingType.durationMinutes,
                                    location,
                                    isMeet(l),
                                    manageUrl(l.booking),
                                    ownerManageUrl(l.booking),
                                    cancelUrl(l.booking),
                                    l.answers)
                            .setLocale(locale)
                            .render();
                });
        // Re-send the (bumped-sequence) REQUEST .ics to every active guest so their calendar updates too.
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_updated_subject(label(l)));
    }

    void handleCancelled(BookingCancelled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        // No location/meet link in the cancellation body; .ics attached when Google is not connected.
        sendForKindLocaleAware(
                l,
                resolveLocation(l),
                messages.forLocale(inviteeLocale).email_cancelled_subject(label(l)),
                messages.forLocale(ownerLocale).email_cancelled_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return Templates.cancellation(
                                    role,
                                    e.byOwner(),
                                    locale.getLanguage(),
                                    l.booking.inviteeName,
                                    l.owner.ownerName,
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    label(l),
                                    start,
                                    l.meetingType.durationMinutes)
                            .setLocale(locale)
                            .render();
                });
        sendGuestCancels(l, messages.forLocale(inviteeLocale).email_cancelled_subject(label(l)));
    }

    void handleReminder(ReminderDue e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverReminder(l, mailSender::send);
    }

    /** Renders + delivers the reminder email through the given sink (direct or outbox). */
    private void deliverReminder(Loaded l, MailSink sink) {
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        sendForKindLocaleAware(
                l,
                location,
                messages.forLocale(inviteeLocale).email_reminder_subject(label(l)),
                messages.forLocale(ownerLocale).email_reminder_subject(label(l)),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return Templates.reminder(
                                    role,
                                    locale.getLanguage(),
                                    INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName,
                                    l.booking.inviteeName,
                                    label(l),
                                    start,
                                    l.meetingType.durationMinutes,
                                    location,
                                    isMeet(l),
                                    manageUrl(l.booking),
                                    ownerManageUrl(l.booking),
                                    cancelUrl(l.booking),
                                    l.answers)
                            .setLocale(locale)
                            .render();
                },
                sink);
    }

    /** Renders the reminder email and enqueues it in the CALLER's transaction (atomic with the claim). */
    public void enqueueReminder(Long bookingId) {
        var l = read(bookingId);
        if (l == null) return;
        deliverReminder(l, EmailService::enqueueToOutbox);
    }

    // --- guest fan-out: guests always get a calit mail; .ics is attached only when Google is NOT
    //     connected (when connected, guests are Google event attendees and Google sends the invite). ---

    /** REQUEST .ics + invite body to every active guest, in the booking (invitee's) locale. */
    private void sendGuestInvites(Loaded l, String location, String subject) {
        List<BookingGuest> guests =
                QuarkusTransaction.requiringNew().call(() -> BookingGuest.activeForBooking(l.booking.id));
        if (guests.isEmpty()) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        String start = format(l.booking.startUtc, l.zone, locale);
        for (BookingGuest g : guests) {
            byte[] ics = calendarPort.isConnected(l.owner.ownerId) ? null : guestIcs(l, g, location, IcsMethod.REQUEST);
            String body = Templates.guestInvite(
                            locale.getLanguage(),
                            g.email,
                            l.booking.inviteeName,
                            label(l),
                            start,
                            l.meetingType.durationMinutes,
                            location,
                            isMeet(l),
                            declineGuestUrl(g))
                    .setLocale(locale)
                    .render();
            mailSender.send(fromName(l), g.email, subject, body, ics);
        }
    }

    /** CANCEL .ics + cancel body to every active guest. */
    private void sendGuestCancels(Loaded l, String subject) {
        List<BookingGuest> guests =
                QuarkusTransaction.requiringNew().call(() -> BookingGuest.activeForBooking(l.booking.id));
        if (guests.isEmpty()) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        for (BookingGuest g : guests) {
            mailSender.send(
                    fromName(l),
                    g.email,
                    subject,
                    guestCancelBody(l, g, locale),
                    calendarPort.isConnected(l.owner.ownerId) ? null : guestIcs(l, g, null, IcsMethod.CANCEL));
        }
    }

    void handleGuestRemoved(GuestRemoved e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        BookingGuest guest = QuarkusTransaction.requiringNew().call(() -> BookingGuest.findById(e.guestId()));
        if (guest == null) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        mailSender.send(
                fromName(l),
                guest.email,
                messages.forLocale(locale).email_cancelled_subject(label(l)),
                guestCancelBody(l, guest, locale),
                calendarPort.isConnected(l.owner.ownerId) ? null : guestIcs(l, guest, null, IcsMethod.CANCEL));
    }

    void handleGuestDeclined(GuestDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        BookingGuest guest = QuarkusTransaction.requiringNew().call(() -> BookingGuest.findById(e.guestId()));
        if (guest == null) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        String start = format(l.booking.startUtc, l.zone, locale);
        // 1) cancel .ics to the departing guest (omit .ics when Google natively notifies)
        mailSender.send(
                fromName(l),
                guest.email,
                messages.forLocale(locale).email_cancelled_subject(label(l)),
                guestCancelBody(l, guest, locale),
                calendarPort.isConnected(l.owner.ownerId) ? null : guestIcs(l, guest, null, IcsMethod.CANCEL));
        // 2) notify the invitee so they can reschedule
        String inviteeBody = Templates.guestDeclinedNotice(
                        locale.getLanguage(), l.booking.inviteeName, guest.email, label(l), start, manageUrl(l.booking))
                .setLocale(locale)
                .render();
        mailSender.send(
                fromName(l),
                l.booking.inviteeEmail,
                messages.forLocale(locale).email_guest_declined_subject(label(l)),
                inviteeBody,
                null);
    }

    /** Renders the guest cancel body in the given locale. */
    private String guestCancelBody(Loaded l, BookingGuest g, Locale locale) {
        return Templates.guestCancel(
                        locale.getLanguage(), g.email, label(l), format(l.booking.startUtc, l.zone, locale))
                .setLocale(locale)
                .render();
    }

    /**
     * Builds a guest .ics: owner is ORGANIZER, this guest is the ATTENDEE, SEQUENCE = booking.icsSequence.
     * attendeeRsvp=false suppresses the calendar Yes/No buttons — guests respond only via calit's decline
     * link (a calendar reply would go to the owner's mailbox and calit would never see it).
     */
    private byte[] guestIcs(Loaded l, BookingGuest g, String location, IcsMethod method) {
        return IcsBuilder.build(IcsEvent.builder()
                        .uid(l.booking.manageToken)
                        .summary(label(l))
                        .description(l.booking.effectiveDescription(l.meetingType))
                        .location(location)
                        .organizer(new IcsBuilder.Party(l.owner.ownerName, mailFrom))
                        .attendee(new IcsBuilder.Party(g.email, g.email))
                        .start(l.booking.startUtc)
                        .end(l.booking.endUtc)
                        .method(method)
                        .sequence(l.booking.icsSequence)
                        .attendeeRsvp(false)
                        .build())
                .getBytes(StandardCharsets.UTF_8);
    }

    private String declineGuestUrl(BookingGuest g) {
        return baseUrl + "/guest/" + g.declineToken + "/decline";
    }

    // --- recipient selection + send plumbing ---

    /**
     * Locale-aware delivery (default SMTP sink). Invitee and owner may get different subjects and
     * body renderings according to their respective locales.
     */
    private void sendForKindLocaleAware(
            Loaded l,
            String icsLocation,
            String inviteeSubject,
            String ownerSubject,
            UnaryOperator<String> bodyForRole) {
        sendForKindLocaleAware(l, icsLocation, inviteeSubject, ownerSubject, bodyForRole, mailSender::send);
    }

    /**
     * Renders the body (per recipient role) and delivers it through {@code sink} to the selected
     * recipients using per-recipient locale for subject and body. Invitee is always notified — the
     * calit email carries manage/cancel links that Google's calendar invite does not. Owner included
     * iff {@code ownerNotificationsEnabled}. When Google is connected it natively notifies invitee
     * + owner (they are event attendees), so calit attaches NO .ics. When NOT connected, calit's
     * .ics is the only calendar source.
     */
    private void sendForKindLocaleAware(
            Loaded l,
            String icsLocation,
            String inviteeSubject,
            String ownerSubject,
            UnaryOperator<String> bodyForRole,
            MailSink sink) {
        // Invariant: when Google is connected it natively notifies invitee + owner (they are event
        // attendees), so calit attaches NO .ics. We still send the email — it carries the manage/cancel
        // links Google's invite does not. When NOT connected, calit's .ics is the only calendar source.
        boolean googleNotifies = calendarPort.isConnected(l.owner.ownerId);
        byte[] ics = googleNotifies
                ? null
                : IcsBuilder.build(IcsEvent.builder()
                                .uid(l.booking.manageToken)
                                .summary(label(l))
                                .description(l.booking.effectiveDescription(l.meetingType))
                                .location(icsLocation)
                                .organizer(new IcsBuilder.Party(l.owner.ownerName, mailFrom))
                                .attendee(new IcsBuilder.Party(l.booking.inviteeName, l.booking.inviteeEmail))
                                .start(l.booking.startUtc)
                                .end(l.booking.endUtc)
                                .build())
                        .getBytes(StandardCharsets.UTF_8);
        var from = fromName(l);

        sink.deliver(from, l.booking.inviteeEmail, inviteeSubject, bodyForRole.apply(INVITEE_ROLE), ics);
        if (l.owner.ownerNotificationsEnabled) {
            sink.deliver(from, l.owner.ownerEmail, ownerSubject, bodyForRole.apply(OWNER_ROLE), ics);
        }
    }

    /** Per-message From display name for booking mail: "{owner} via calit", or null if no owner name. */
    private String fromName(Loaded l) {
        // ponytail: "via calit" is the product name; make it config (app.brand-name) only on a real rebrand.
        return l.owner.ownerName == null ? null : l.owner.ownerName.replaceAll("[\\r\\n]", " ") + " via calit";
    }

    private String manageUrl(Booking b) {
        return baseUrl + "/booking/" + b.manageToken + "/manage";
    }

    /** Base path of the owner's per-booking actions on /me (manage/approve/decline). */
    // S1075: an internal JAX-RS route prefix, not a deployment-configurable URI -- calit hardcodes all routes.
    @SuppressWarnings("java:S1075")
    private static final String ME_BOOKINGS_PATH = "/me/bookings/";

    private String ownerManageUrl(Booking b) {
        return baseUrl + ME_BOOKINGS_PATH + b.id + "/manage";
    }

    /** Owner authenticated approve link with the token nonce; null when no approval token exists. */
    private String approveUrl(Booking b) {
        return b.approvalToken == null ? null : baseUrl + ME_BOOKINGS_PATH + b.id + "/approve?t=" + b.approvalToken;
    }

    private String declineUrl(Booking b) {
        return b.approvalToken == null ? null : baseUrl + ME_BOOKINGS_PATH + b.id + "/decline?t=" + b.approvalToken;
    }

    private String cancelUrl(Booking b) {
        return baseUrl + "/booking/" + b.manageToken + "/cancel";
    }

    /** The meeting label shown in every mail: the booking's title override, else the type name. */
    private static String label(Loaded l) {
        return l.booking.effectiveTitle(l.meetingType);
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
    private record Loaded(
            Booking booking, MeetingType meetingType, OwnerSettings owner, ZoneId zone, List<AnswerLine> answers) {}

    /** One rendered custom-field answer: human label + submitted value. Public for Qute access. */
    public record AnswerLine(String label, String value) {}
}
