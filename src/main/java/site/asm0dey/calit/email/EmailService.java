package site.asm0dey.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
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
import site.asm0dey.calit.i18n.AppMessages;

@ApplicationScoped
// S6813: CDI field injection is the established pattern across this codebase's beans.
@SuppressWarnings("java:S6813")
public class EmailService {

    public static final String RECIPIENT_ROLE = "recipientRole";
    public static final String RECIPIENT_ROLE_DISPLAY = "recipientRoleDisplay";
    /** Recipient-role values passed to the per-role body builder. */
    private static final String INVITEE_ROLE = "invitee";

    private static final String OWNER_ROLE = "owner";
    private static final String GUEST_ROLE = "guest";
    public static final String DECLINE_GUEST_URL = "declineGuestUrl";
    public static final String GUEST_EMAIL_DATA = "guestEmail";
    public static final String INVITEE_NAME = "inviteeName";
    public static final String MEETING_TYPE_NAME = "meetingTypeName";
    public static final String START_TIME = "startTime";
    public static final String DURATION_MINUTES = "durationMinutes";
    public static final String LOCATION = "location";
    public static final String IS_MEET_LINK = "isMeetLink";
    public static final String MANAGE_URL = "manageUrl";
    /** Owner-only login-gated manage (reschedule/cancel) link on /me; rendered only for the owner copy. */
    public static final String OWNER_MANAGE_URL = "ownerManageUrl";

    public static final String ANSWERS = "answers";

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

    @Inject
    @Location("email/guest-invite.html")
    Template guestInvite;

    @Inject
    @Location("email/guest-cancel.html")
    Template guestCancel;

    @Inject
    @Location("email/guest-declined.html")
    Template guestDeclinedNotice;

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

    /** Which invitee-delivery rule a kind follows. */
    private enum InviteeRule {
        /** Always send to invitee (no Google event exists for this state). */
        ALWAYS,
        /** Send to invitee only when Google is NOT connected (Google notifies otherwise). */
        FALLBACK,
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
                InviteeRule.ALWAYS,
                l,
                location,
                messages.forLocale(inviteeLocale).email_requested_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_requested_subject(l.meetingType.name),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return requested
                            .instance()
                            .setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(CANCEL_URL, cancelUrl(l.booking))
                            .data(APPROVE_URL, approveUrl(l.booking))
                            .data(DECLINE_URL, declineUrl(l.booking))
                            .data(ANSWERS, l.answers)
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
                InviteeRule.FALLBACK,
                l,
                location,
                messages.forLocale(inviteeLocale).email_confirmed_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_confirmed_subject(l.meetingType.name),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return confirmation
                            .instance()
                            .setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))
                            .data(CANCEL_URL, cancelUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                });
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_confirmed_subject(l.meetingType.name));
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
                InviteeRule.FALLBACK,
                l,
                location,
                messages.forLocale(inviteeLocale).email_approved_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_approved_subject(l.meetingType.name),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return confirmation
                            .instance()
                            .setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))
                            .data(CANCEL_URL, cancelUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                });
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_confirmed_subject(l.meetingType.name));
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
                    l,
                    messages.forLocale(AppLocales.pick(l.booking.locale)).email_cancelled_subject(l.meetingType.name));
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
                InviteeRule.ALWAYS,
                l,
                resolveLocation(l),
                messages.forLocale(inviteeLocale).email_declined_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_declined_subject(l.meetingType.name),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return declined.instance()
                            .setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
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
                InviteeRule.FALLBACK,
                l,
                location,
                messages.forLocale(inviteeLocale).email_rescheduled_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_rescheduled_subject(l.meetingType.name),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var newStart = INVITEE_ROLE.equals(role) ? inviteeNewStart : ownerNewStart;
                    var oldStart = INVITEE_ROLE.equals(role) ? inviteeOldStart : ownerOldStart;
                    return reschedule
                            .instance()
                            .setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, newStart)
                            .data("oldStartTime", oldStart)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))
                            .data(CANCEL_URL, cancelUrl(l.booking))
                            .data(ANSWERS, l.answers)
                            .render();
                });
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_rescheduled_subject(l.meetingType.name));
    }

    void handleCancelled(BookingCancelled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        Locale ownerLocale = AppLocales.pick(l.owner.locale);
        String inviteeStart = format(l.booking.startUtc, l.zone, inviteeLocale);
        String ownerStart = format(l.booking.startUtc, l.zone, ownerLocale);
        // No location/meet link in the cancellation body; .ics still attached describing the removed event.
        sendForKindLocaleAware(
                InviteeRule.FALLBACK,
                l,
                resolveLocation(l),
                messages.forLocale(inviteeLocale).email_cancelled_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_cancelled_subject(l.meetingType.name),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return cancellation
                            .instance()
                            .setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .render();
                });
        sendGuestCancels(l, messages.forLocale(inviteeLocale).email_cancelled_subject(l.meetingType.name));
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
                InviteeRule.FALLBACK,
                l,
                location,
                messages.forLocale(inviteeLocale).email_reminder_subject(l.meetingType.name),
                messages.forLocale(ownerLocale).email_reminder_subject(l.meetingType.name),
                role -> {
                    var locale = INVITEE_ROLE.equals(role) ? inviteeLocale : ownerLocale;
                    var start = INVITEE_ROLE.equals(role) ? inviteeStart : ownerStart;
                    return reminder.instance()
                            .setLocale(locale)
                            .data(RECIPIENT_ROLE, role)
                            .data(RECIPIENT_ROLE_DISPLAY, localizedRole(role, locale))
                            .data("lang", locale.getLanguage())
                            .data(INVITEE_NAME, l.booking.inviteeName)
                            .data(GREETING_NAME, INVITEE_ROLE.equals(role) ? l.booking.inviteeName : l.owner.ownerName)
                            .data(MEETING_TYPE_NAME, l.meetingType.name)
                            .data(START_TIME, start)
                            .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                            .data(LOCATION, location)
                            .data(IS_MEET_LINK, isMeet(l))
                            .data(MANAGE_URL, manageUrl(l.booking))
                            .data(OWNER_MANAGE_URL, ownerManageUrl(l.booking))
                            .data(CANCEL_URL, cancelUrl(l.booking))
                            .data(ANSWERS, l.answers)
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

    // --- guest fan-out: guests are notified ONLY by calit .ics (no Google path), always. ---

    /** REQUEST .ics + invite body to every active guest, in the booking (invitee's) locale. */
    private void sendGuestInvites(Loaded l, String location, String subject) {
        List<BookingGuest> guests =
                QuarkusTransaction.requiringNew().call(() -> BookingGuest.activeForBooking(l.booking.id));
        if (guests.isEmpty()) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        String start = format(l.booking.startUtc, l.zone, locale);
        for (BookingGuest g : guests) {
            byte[] ics = guestIcs(l, g, location, IcsMethod.REQUEST);
            String body = guestInvite
                    .instance()
                    .setLocale(locale)
                    .data(RECIPIENT_ROLE, GUEST_ROLE)
                    .data(RECIPIENT_ROLE_DISPLAY, messages.forLocale(locale).email_role_guest())
                    .data("lang", locale.getLanguage())
                    .data(GREETING_NAME, g.email)
                    .data(INVITEE_NAME, l.booking.inviteeName)
                    .data(MEETING_TYPE_NAME, l.meetingType.name)
                    .data(START_TIME, start)
                    .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                    .data(LOCATION, location)
                    .data(IS_MEET_LINK, isMeet(l))
                    .data(DECLINE_GUEST_URL, declineGuestUrl(g))
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
                    guestIcs(l, g, null, IcsMethod.CANCEL));
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
                messages.forLocale(locale).email_cancelled_subject(l.meetingType.name),
                guestCancelBody(l, guest, locale),
                guestIcs(l, guest, null, IcsMethod.CANCEL));
    }

    void handleGuestDeclined(GuestDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        BookingGuest guest = QuarkusTransaction.requiringNew().call(() -> BookingGuest.findById(e.guestId()));
        if (guest == null) return;
        Locale locale = AppLocales.pick(l.booking.locale);
        String start = format(l.booking.startUtc, l.zone, locale);
        // 1) cancel .ics to the departing guest
        mailSender.send(
                fromName(l),
                guest.email,
                messages.forLocale(locale).email_cancelled_subject(l.meetingType.name),
                guestCancelBody(l, guest, locale),
                guestIcs(l, guest, null, IcsMethod.CANCEL));
        // 2) notify the invitee so they can reschedule
        String inviteeBody = guestDeclinedNotice
                .instance()
                .setLocale(locale)
                .data("lang", locale.getLanguage())
                .data(GREETING_NAME, l.booking.inviteeName)
                .data(RECIPIENT_ROLE_DISPLAY, messages.forLocale(locale).email_role_invitee())
                .data(GUEST_EMAIL_DATA, guest.email)
                .data(MEETING_TYPE_NAME, l.meetingType.name)
                .data(START_TIME, start)
                .data(MANAGE_URL, manageUrl(l.booking))
                .render();
        mailSender.send(
                fromName(l),
                l.booking.inviteeEmail,
                messages.forLocale(locale).email_guest_declined_subject(l.meetingType.name),
                inviteeBody,
                null);
    }

    /** Renders the guest cancel body in the given locale. */
    private String guestCancelBody(Loaded l, BookingGuest g, Locale locale) {
        return guestCancel
                .instance()
                .setLocale(locale)
                .data(RECIPIENT_ROLE, GUEST_ROLE)
                .data(RECIPIENT_ROLE_DISPLAY, messages.forLocale(locale).email_role_guest())
                .data("lang", locale.getLanguage())
                .data(GREETING_NAME, g.email)
                .data(MEETING_TYPE_NAME, l.meetingType.name)
                .data(START_TIME, format(l.booking.startUtc, l.zone, locale))
                .data(DURATION_MINUTES, l.meetingType.durationMinutes)
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
                        .summary(l.meetingType.name)
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
            InviteeRule rule,
            Loaded l,
            String icsLocation,
            String inviteeSubject,
            String ownerSubject,
            UnaryOperator<String> bodyForRole) {
        sendForKindLocaleAware(rule, l, icsLocation, inviteeSubject, ownerSubject, bodyForRole, mailSender::send);
    }

    /**
     * Renders the body (per recipient role) and delivers it through {@code sink} to the selected
     * recipients, each with the .ics, using per-recipient locale for subject and body.
     * Owner included iff {@code ownerNotificationsEnabled}; invitee per {@code rule} and
     * {@code calendarPort.isConnected()}. No mail if the recipient set is empty.
     */
    private void sendForKindLocaleAware(
            InviteeRule rule,
            Loaded l,
            String icsLocation,
            String inviteeSubject,
            String ownerSubject,
            UnaryOperator<String> bodyForRole,
            MailSink sink) {
        var sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected(l.owner.ownerId);
        boolean sendOwner = l.owner.ownerNotificationsEnabled;
        var from = fromName(l);

        byte[] ics = IcsBuilder.build(IcsEvent.builder()
                        .uid(l.booking.manageToken)
                        .summary(l.meetingType.name)
                        .location(icsLocation)
                        .organizer(new IcsBuilder.Party(l.owner.ownerName, mailFrom))
                        .attendee(new IcsBuilder.Party(l.booking.inviteeName, l.booking.inviteeEmail))
                        .start(l.booking.startUtc)
                        .end(l.booking.endUtc)
                        .build())
                .getBytes(StandardCharsets.UTF_8);

        if (sendInvitee) {
            sink.deliver(from, l.booking.inviteeEmail, inviteeSubject, bodyForRole.apply(INVITEE_ROLE), ics);
        }
        if (sendOwner) {
            sink.deliver(from, l.owner.ownerEmail, ownerSubject, bodyForRole.apply(OWNER_ROLE), ics);
        }
    }

    /** Returns the localized display word for the recipient role in the email footer. */
    private String localizedRole(String role, Locale locale) {
        AppMessages m = messages.forLocale(locale);
        return INVITEE_ROLE.equals(role) ? m.email_role_invitee() : m.email_role_owner();
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
