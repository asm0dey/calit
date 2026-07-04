package site.asm0dey.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.MeetingHosts;
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

    /** Recipient-role values passed to the per-role body builder. */
    private static final String INVITEE_ROLE = "invitee";

    private static final String OWNER_ROLE = "owner";

    @Inject
    MailSender mailSender;

    @Inject
    AppMessageResolver messages;

    @Inject
    CalendarPort calendarPort;

    @Inject
    MeetingHosts meetingHosts;

    @ConfigProperty(name = "app.base-url")
    String baseUrl;

    /**
     * The address every mail is actually sent From. Gmail refuses to render an iTIP REQUEST whose
     * ORGANIZER differs from the message sender ("Unable to load event"), so the .ics ORGANIZER must
     * use this address; the owner's real name is kept as the ORGANIZER CN. (Gmail ignores SENT-BY.)
     */
    @ConfigProperty(name = "app.mail-from")
    String mailFrom;

    /**
     * Sends a password-reset link. Caller has already resolved the destination address.
     * {@code expiresAt} is the reset token's expiry: if the mail can't be sent now and has to fall
     * back to the outbox, retries stop at that instant so a dead-link email is never delivered.
     * {@code locale} drives any {msg:} keys rendered in the template body.
     */
    public void sendPasswordReset(String toEmail, String resetUrl, Instant expiresAt, Locale locale) {
        String body = Templates.passwordReset(locale.getLanguage(), resetUrl)
                .setLocale(locale)
                .render();
        mailSender.send(
                null, toEmail, messages.forLocale(locale).email_password_reset_subject(), body, null, expiresAt);
    }

    /**
     * Sends an account-invite email carrying a set-password activation link (same single-use token
     * machinery as a password reset). {@code inviter} is the admin's display email, {@code host} the
     * app base URL, {@code expiresAt} the token expiry (retries stop there so no dead link is sent).
     * {@code locale} drives the {msg:} keys in the body.
     */
    public void sendInvite(
            String toEmail, String activationUrl, String inviter, String host, Instant expiresAt, Locale locale) {
        String body = Templates.invite(locale.getLanguage(), activationUrl, inviter, host)
                .setLocale(locale)
                .render();
        mailSender.send(null, toEmail, messages.forLocale(locale).email_invite_subject(), body, null, expiresAt);
    }

    /**
     * Critical operational alert: the owner's Google account is disconnected and their booking page
     * is paused. Sent regardless of {@code ownerNotificationsEnabled} (that flag governs only routine
     * booking notifications). Links to the Google settings page so the owner can reconnect.
     * {@code locale} drives any {msg:} keys rendered in the template body.
     */
    public void sendGoogleDisconnected(String toEmail, String accountEmail, Locale locale) {
        var reconnectUrl = baseUrl + "/me/google";
        String body = Templates.googleDisconnected(
                        locale.getLanguage(), accountEmail == null ? "your account" : accountEmail, reconnectUrl)
                .setLocale(locale)
                .render();
        mailSender.send(null, toEmail, messages.forLocale(locale).email_google_disconnected_subject(), body, null);
    }

    // basePath = "email": @Location on individual @CheckedTemplate native methods is NOT honored by
    // Qute's build-time processor (only @CheckedTemplate.basePath()/defaultName() drive path
    // resolution) -- confirmed by inspecting QuteProcessor#collectCheckedTemplates, which never reads
    // a @Location annotation off the method target. Without basePath the method would resolve to
    // EmailService/reminder instead of email/reminder.html.
    /**
     * Typed bindings for the email templates. Non-obvious shared params: {@code greetingName} is
     * role-aware (invitee name on the invitee copy, owner name on the owner copy); {@code byOwner}
     * says the host — not the invitee — drove the change, which flips the reschedule/cancel/update
     * wording; {@code approveUrl}/{@code declineUrl}/{@code ownerManageUrl} are owner-only and passed
     * null for the invitee copy (the template renders them only for the owner).
     */
    @CheckedTemplate(basePath = "email")
    static class Templates {
        private Templates() {}

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

        static native TemplateInstance passwordReset(String lang, String resetUrl);

        static native TemplateInstance invite(String lang, String activationUrl, String inviter, String host);

        static native TemplateInstance googleDisconnected(String lang, String accountEmail, String reconnectUrl);

        static native TemplateInstance hostConsent(
                String lang, String greetingName, String creatorName, String meetingTypeName, String consentUrl);
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

    void onHostConsent(@Observes(during = TransactionPhase.AFTER_SUCCESS) HostConsentRequested e) {
        handleHostConsent(e);
    }

    // --- Package-private helpers: own their transaction, directly unit-testable. ---

    void handleRequested(BookingRequested e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        sendForKindLocaleAware(
                l,
                location,
                locale -> messages.forLocale(locale).email_requested_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.requested(
                                role,
                                locale.getLanguage(),
                                greetingName,
                                l.booking.inviteeName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                l.meetingType.durationMinutes,
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                approveUrl(linkBooking),
                                declineUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
                // Actionable: an opted-out host must still be able to approve/decline their row, or
                // the group deadlocks. (Single-host behavior is unaffected -- see the send core.)
                true);
    }

    void handleConfirmed(BookingConfirmed e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        sendForKindLocaleAware(
                l,
                location,
                locale -> messages.forLocale(locale).email_confirmed_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.confirmation(
                                role,
                                locale.getLanguage(),
                                greetingName,
                                l.booking.inviteeName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                l.meetingType.durationMinutes,
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                ownerManageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
                false);
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_confirmed_subject(label(l)));
    }

    void handleApproved(BookingApproved e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        // Same body as confirmed (now confirmed after approval); only subject differs. (Group
        // bookings never fire BookingApproved -- the last host's approval fires BookingConfirmed
        // instead -- so this handler is single-host only; the fan-out branch never triggers here.)
        sendForKindLocaleAware(
                l,
                location,
                locale -> messages.forLocale(locale).email_approved_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.confirmation(
                                role,
                                locale.getLanguage(),
                                greetingName,
                                l.booking.inviteeName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                l.meetingType.durationMinutes,
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                ownerManageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
                false);
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
        // No Google event ever existed -> always notify the invitee. No answers, no location link.
        sendForKindLocaleAware(
                l,
                resolveLocation(l),
                locale -> messages.forLocale(locale).email_declined_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.declined(
                                role,
                                locale.getLanguage(),
                                greetingName,
                                l.booking.inviteeName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                l.meetingType.durationMinutes)
                        .setLocale(locale)
                        .render(),
                sink,
                false);
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
        sendForKindLocaleAware(
                l,
                location,
                locale -> messages.forLocale(locale).email_rescheduled_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.reschedule(
                                role,
                                e.byOwner(),
                                locale.getLanguage(),
                                l.booking.inviteeName,
                                l.owner.ownerName,
                                greetingName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                format(e.oldStartUtc(), zone, locale),
                                l.meetingType.durationMinutes,
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                ownerManageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
                false);
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_rescheduled_subject(label(l)));
    }

    void handleDetailsChanged(BookingDetailsChanged e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        var location = resolveLocation(l);
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        String desc = l.booking.effectiveDescription(l.meetingType);
        sendForKindLocaleAware(
                l,
                location,
                locale -> messages.forLocale(locale).email_updated_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.updated(
                                role,
                                e.byOwner(),
                                desc,
                                locale.getLanguage(),
                                l.booking.inviteeName,
                                l.owner.ownerName,
                                greetingName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                l.meetingType.durationMinutes,
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                ownerManageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
                false);
        // Re-send the (bumped-sequence) REQUEST .ics to every active guest so their calendar updates too.
        sendGuestInvites(l, location, messages.forLocale(inviteeLocale).email_updated_subject(label(l)));
    }

    void handleCancelled(BookingCancelled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        // No location/meet link in the cancellation body; .ics attached when Google is not connected.
        sendForKindLocaleAware(
                l,
                resolveLocation(l),
                locale -> messages.forLocale(locale).email_cancelled_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.cancellation(
                                role,
                                e.byOwner(),
                                locale.getLanguage(),
                                l.booking.inviteeName,
                                l.owner.ownerName,
                                greetingName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                l.meetingType.durationMinutes)
                        .setLocale(locale)
                        .render(),
                false);
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
        sendForKindLocaleAware(
                l,
                location,
                locale -> messages.forLocale(locale).email_reminder_subject(label(l)),
                (role, locale, zone, greetingName, linkBooking) -> Templates.reminder(
                                role,
                                locale.getLanguage(),
                                greetingName,
                                l.booking.inviteeName,
                                label(l),
                                format(l.booking.startUtc, zone, locale),
                                l.meetingType.durationMinutes,
                                location,
                                isMeet(l),
                                manageUrl(linkBooking),
                                ownerManageUrl(linkBooking),
                                cancelUrl(linkBooking),
                                l.answers)
                        .setLocale(locale)
                        .render(),
                sink,
                false);
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

    /**
     * A pending co-host row was created; email the candidate a one-click {@code /consent/{token}}
     * link. Actionable (like the approval-needed mail) -- there is no {@code OwnerSettings} row yet
     * to gate on (the candidate isn't a confirmed host until they accept), so this always sends.
     */
    void handleHostConsent(HostConsentRequested e) {
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings cohost = OwnerSettings.forOwner(e.cohostOwnerId());
            if (cohost == null) return;
            MeetingType type = MeetingType.findById(e.meetingTypeId());
            if (type == null) return;
            OwnerSettings creator = OwnerSettings.forOwner(type.ownerId);
            Locale locale = AppLocales.pick(cohost.locale);
            String consentUrl = baseUrl + "/consent/" + e.consentToken();
            String body = Templates.hostConsent(
                            locale.getLanguage(),
                            cohost.ownerName,
                            creator == null ? null : creator.ownerName,
                            type.name,
                            consentUrl)
                    .setLocale(locale)
                    .render();
            mailSender.send(
                    null,
                    cohost.ownerEmail,
                    messages.forLocale(locale).email_host_consent_subject(type.name),
                    body,
                    null);
        });
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
     * Per-recipient body renderer: {@code role} is {@link #INVITEE_ROLE} or {@link #OWNER_ROLE};
     * {@code locale}/{@code zone}/{@code greetingName} are that recipient's own (for a group
     * booking's owner side, that HOST's own {@code OwnerSettings}); {@code linkBooking} is the row
     * whose tokens back the manage/cancel/approve/decline URLs (that host's own row for a group
     * owner copy, the lead row otherwise).
     */
    @FunctionalInterface
    private interface RecipientBodyRenderer {
        String render(String role, Locale locale, ZoneId zone, String greetingName, Booking linkBooking);
    }

    /**
     * Locale-aware delivery (default SMTP sink). Invitee and owner(s) may get different subjects and
     * body renderings according to their respective locales.
     */
    private void sendForKindLocaleAware(
            Loaded l,
            String icsLocation,
            Function<Locale, String> subjectForLocale,
            RecipientBodyRenderer bodyForRecipient,
            boolean actionable) {
        sendForKindLocaleAware(l, icsLocation, subjectForLocale, bodyForRecipient, mailSender::send, actionable);
    }

    /**
     * Renders the body (per recipient) and delivers it through {@code sink} using per-recipient
     * locale for subject and body. Invitee is always notified exactly ONCE, keyed on the lead row —
     * the calit email carries manage/cancel links that Google's calendar invite does not. For a
     * SINGLE-host booking the sole owner is included iff {@code ownerNotificationsEnabled} (unchanged
     * pre-multi-host behavior). For a GROUP booking, EACH host gets their own personalized copy
     * (their own locale/name/booking-row tokens); {@code actionable} kinds (approval-needed) ignore
     * that host's {@code ownerNotificationsEnabled} -- an opted-out host must still be able to
     * approve/decline their row, or the whole group deadlocks. When Google is connected it natively
     * notifies invitee + owner(s) (they are event attendees), so calit attaches NO .ics. When NOT
     * connected, calit's .ics is the only calendar source.
     */
    private void sendForKindLocaleAware(
            Loaded l,
            String icsLocation,
            Function<Locale, String> subjectForLocale,
            RecipientBodyRenderer bodyForRecipient,
            MailSink sink,
            boolean actionable) {
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

        Locale inviteeLocale = AppLocales.pick(l.booking.locale);
        sink.deliver(
                from,
                l.booking.inviteeEmail,
                subjectForLocale.apply(inviteeLocale),
                bodyForRecipient.render(INVITEE_ROLE, inviteeLocale, l.zone, l.booking.inviteeName, l.booking),
                ics);

        if (l.booking.groupId != null) {
            for (HostDelivery hd : l.hostDeliveries) {
                if (!actionable && !hd.settings.ownerNotificationsEnabled) continue;
                Locale hostLocale = AppLocales.pick(hd.settings.locale);
                ZoneId hostZone = ZoneId.of(hd.settings.timezone);
                sink.deliver(
                        from,
                        hd.settings.ownerEmail,
                        subjectForLocale.apply(hostLocale),
                        bodyForRecipient.render(OWNER_ROLE, hostLocale, hostZone, hd.settings.ownerName, hd.booking),
                        ics);
            }
        } else if (l.owner.ownerNotificationsEnabled) {
            Locale ownerLocale = AppLocales.pick(l.owner.locale);
            sink.deliver(
                    from,
                    l.owner.ownerEmail,
                    subjectForLocale.apply(ownerLocale),
                    bodyForRecipient.render(OWNER_ROLE, ownerLocale, l.zone, l.owner.ownerName, l.booking),
                    ics);
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
     * For a group booking ({@code booking.groupId != null}) also eagerly resolves every host's own
     * {@link OwnerSettings} + own booking row (their approve/manage tokens) -- {@link
     * #sendForKindLocaleAware} needs those for the per-host fan-out but runs OUTSIDE this
     * transaction (called after {@link #load} returns), so they can't be looked up lazily there.
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
        List<HostDelivery> hostDeliveries =
                booking.groupId == null ? List.of() : loadHostDeliveries(booking.groupId, type);
        return new Loaded(booking, type, owner, zone, answers, hostDeliveries);
    }

    /** Every accepted host's own {@code OwnerSettings} paired with their own row of this group. */
    private List<HostDelivery> loadHostDeliveries(UUID groupId, MeetingType type) {
        List<Booking> rows = Booking.group(groupId);
        List<HostDelivery> deliveries = new ArrayList<>();
        for (Long hostId : meetingHosts.hostOwnerIds(type)) {
            OwnerSettings settings = OwnerSettings.forOwner(hostId);
            if (settings == null) continue;
            Booking row = rows.stream()
                    .filter(r -> hostId.equals(r.ownerId))
                    .findFirst()
                    .orElse(null);
            if (row == null) continue;
            deliveries.add(new HostDelivery(settings, row));
        }
        return deliveries;
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

    /**
     * Immutable bundle read once in one transaction. {@code hostDeliveries} is empty for a
     * single-host booking ({@code booking.groupId == null}); for a group booking it holds one entry
     * per accepted host (their own {@code OwnerSettings} + their own row of the group).
     */
    private record Loaded(
            Booking booking,
            MeetingType meetingType,
            OwnerSettings owner,
            ZoneId zone,
            List<AnswerLine> answers,
            List<HostDelivery> hostDeliveries) {}

    /** A group booking's per-host delivery target: that host's own settings + own booking row. */
    private record HostDelivery(OwnerSettings settings, Booking booking) {}

    /** One rendered custom-field answer: human label + submitted value. Public for Qute access. */
    public record AnswerLine(String label, String value) {}
}
