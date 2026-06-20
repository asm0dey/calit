package com.calit.email;

import com.calit.booking.Booking;
import com.calit.booking.events.BookingApproved;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingDeclined;
import com.calit.booking.events.BookingRequested;
import com.calit.booking.events.BookingRescheduled;
import com.calit.booking.events.ReminderDue;
import com.calit.domain.BookingField;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@ApplicationScoped
public class EmailService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);
    public static final String RECIPIENT_ROLE = "recipientRole";
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
     */
    public void sendPasswordReset(String toEmail, String resetUrl, Instant expiresAt) {
        String body = passwordReset.data("resetUrl", resetUrl).render();
        mailSender.send(toEmail, "Reset your calit password", body, null, expiresAt);
    }

    /**
     * Critical operational alert: the owner's Google account is disconnected and their booking page
     * is paused. Sent regardless of {@code ownerNotificationsEnabled} (that flag governs only routine
     * booking notifications). Links to the Google settings page so the owner can reconnect.
     */
    public void sendGoogleDisconnected(String toEmail, String accountEmail) {
        String reconnectUrl = baseUrl + "/me/google";
        String body = googleDisconnected
                .data("accountEmail", accountEmail == null ? "your account" : accountEmail)
                .data("reconnectUrl", reconnectUrl)
                .render();
        mailSender.send(toEmail, "Action needed: reconnect your Google Calendar", body, null);
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
        String start = format(l.booking.startUtc, l.zone);
        sendForKind(InviteeRule.ALWAYS, "Booking request received: " + l.meetingType.name, l, location,
                role -> requested
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .data(LOCATION, location)
                        .data(IS_MEET_LINK, isMeet(l))
                        .data(MANAGE_URL, manageUrl(l.booking))
                        .data(ANSWERS, l.answers)
                        .render());
    }

    void handleConfirmed(BookingConfirmed e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        sendForKind(InviteeRule.FALLBACK, "Booking confirmed: " + l.meetingType.name, l, location,
                role -> confirmation
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .data(LOCATION, location)
                        .data(IS_MEET_LINK, isMeet(l))
                        .data(MANAGE_URL, manageUrl(l.booking))
                        .data(ANSWERS, l.answers)
                        .render());
    }

    void handleApproved(BookingApproved e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        // Same body as confirmed (now confirmed after approval); only subject differs.
        sendForKind(InviteeRule.FALLBACK, "Booking approved: " + l.meetingType.name, l, location,
                role -> confirmation
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .data(LOCATION, location)
                        .data(IS_MEET_LINK, isMeet(l))
                        .data(MANAGE_URL, manageUrl(l.booking))
                        .data(ANSWERS, l.answers)
                        .render());
    }

    void handleDeclined(BookingDeclined e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverDeclined(l, mailSender::send);
    }

    /** Renders + delivers the declined email through the given sink (direct or outbox). */
    private void deliverDeclined(Loaded l, MailSink sink) {
        String start = format(l.booking.startUtc, l.zone);
        // No Google event ever existed -> always notify the invitee. No answers, no location link.
        sendForKind(InviteeRule.ALWAYS, "Booking declined: " + l.meetingType.name, l, resolveLocation(l),
                role -> declined
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .render(),
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
        String newStart = format(l.booking.startUtc, l.zone);
        String oldStart = format(e.oldStartUtc(), l.zone);
        sendForKind(InviteeRule.FALLBACK, "Booking rescheduled: " + l.meetingType.name, l, location,
                role -> reschedule
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, newStart)
                        .data("oldStartTime", oldStart)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .data(LOCATION, location)
                        .data(IS_MEET_LINK, isMeet(l))
                        .data(MANAGE_URL, manageUrl(l.booking))
                        .data(ANSWERS, l.answers)
                        .render());
    }

    void handleCancelled(BookingCancelled e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        String start = format(l.booking.startUtc, l.zone);
        // No location/meet link in the cancellation body; .ics still attached describing the removed event.
        sendForKind(InviteeRule.FALLBACK, "Booking cancelled: " + l.meetingType.name, l, resolveLocation(l),
                role -> cancellation
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .render());
    }

    void handleReminder(ReminderDue e) {
        Loaded l = load(e.bookingId());
        if (l == null) return;
        deliverReminder(l, mailSender::send);
    }

    /** Renders + delivers the reminder email through the given sink (direct or outbox). */
    private void deliverReminder(Loaded l, MailSink sink) {
        String location = resolveLocation(l);
        String start = format(l.booking.startUtc, l.zone);
        sendForKind(InviteeRule.FALLBACK, "Reminder: " + l.meetingType.name, l, location,
                role -> reminder
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .data(LOCATION, location)
                        .data(IS_MEET_LINK, isMeet(l))
                        .data(MANAGE_URL, manageUrl(l.booking))
                        .data(ANSWERS, l.answers)
                        .render(),
                sink);
    }

    /** Renders the reminder email and enqueues it in the CALLER's transaction (atomic with the claim). */
    public void enqueueReminder(Long bookingId) {
        Loaded l = read(bookingId);
        if (l == null) return;
        deliverReminder(l, EmailService::enqueueToOutbox);
    }

    // --- recipient selection + send plumbing ---

    /** Default delivery: direct SMTP via MailSender (outbox fallback on failure). Used by event handlers. */
    private void sendForKind(InviteeRule rule, String subject, Loaded l, String icsLocation,
                             Function<String, String> bodyForRole) {
        sendForKind(rule, subject, l, icsLocation, bodyForRole, mailSender::send);
    }

    /**
     * Renders the body (per recipient role) and delivers it through {@code sink} to the selected
     * recipients, each with the .ics. Owner included iff {@code ownerNotificationsEnabled}; invitee
     * per {@code rule} and {@code calendarPort.isConnected()}. No mail if the recipient set is empty.
     */
    private void sendForKind(InviteeRule rule, String subject, Loaded l, String icsLocation,
                             Function<String, String> bodyForRole, MailSink sink) {
        boolean sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected(l.owner.ownerId);
        boolean sendOwner = l.owner.ownerNotificationsEnabled;

        byte[] ics = IcsBuilder.build(l.booking.manageToken, l.meetingType.name, icsLocation,
                l.owner.ownerEmail, l.booking.startUtc, l.booking.endUtc)
                .getBytes(StandardCharsets.UTF_8);

        if (sendInvitee) {
            sink.deliver(l.booking.inviteeEmail, subject, bodyForRole.apply("invitee"), ics);
        }
        if (sendOwner) {
            sink.deliver(l.owner.ownerEmail, subject, bodyForRole.apply("owner"), ics);
        }
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

    private static String format(Instant instant, ZoneId zone) {
        return TIME_FORMAT.format(instant.atZone(zone));
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
