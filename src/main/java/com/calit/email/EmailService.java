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
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
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
    private static final String ICS_CONTENT_TYPE = "text/calendar; charset=UTF-8; method=REQUEST";
    private static final String ICS_FILENAME = "invite.ics";
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
    Mailer mailer;

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

    /** Which invitee-delivery rule a kind follows. */
    private enum InviteeRule {
        /** Always send to invitee (no Google event exists for this state). */
        ALWAYS,
        /** Send to invitee only when Google is NOT connected (Google notifies otherwise). */
        FALLBACK
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
        String start = format(l.booking.startUtc, l.zone);
        // No Google event ever existed -> always notify the invitee. No answers, no location link.
        sendForKind(InviteeRule.ALWAYS, "Booking declined: " + l.meetingType.name, l, resolveLocation(l),
                role -> declined
                        .data(RECIPIENT_ROLE, role)
                        .data(INVITEE_NAME, l.booking.inviteeName)
                        .data(MEETING_TYPE_NAME, l.meetingType.name)
                        .data(START_TIME, start)
                        .data(DURATION_MINUTES, l.meetingType.durationMinutes)
                        .render());
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
                        .render());
    }

    // --- recipient selection + send plumbing ---

    /**
     * Sends the rendered body (per recipient role) to the selected recipients, each with the .ics.
     * Owner is included iff {@code ownerNotificationsEnabled}; invitee per {@code rule} and
     * {@code calendarPort.isConnected()}. No mail is sent if the recipient set is empty.
     */
    private void sendForKind(InviteeRule rule, String subject, Loaded l, String icsLocation,
                             Function<String, String> bodyForRole) {
        boolean sendInvitee = rule == InviteeRule.ALWAYS || !calendarPort.isConnected();
        boolean sendOwner = l.owner.ownerNotificationsEnabled;

        byte[] ics = IcsBuilder.build(l.booking.manageToken, l.meetingType.name, icsLocation,
                l.owner.ownerEmail, l.booking.startUtc, l.booking.endUtc)
                .getBytes(StandardCharsets.UTF_8);

        if (sendInvitee) {
            mailer.send(withIcs(
                    Mail.withHtml(l.booking.inviteeEmail, subject, bodyForRole.apply("invitee")), ics));
        }
        if (sendOwner) {
            mailer.send(withIcs(
                    Mail.withHtml(l.owner.ownerEmail, subject, bodyForRole.apply("owner")), ics));
        }
    }

    private static Mail withIcs(Mail mail, byte[] ics) {
        return mail.addAttachment(ICS_FILENAME, ics, ICS_CONTENT_TYPE);
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
     * Loads the booking + its meeting type + owner settings + custom-field answers inside a fresh
     * transaction. Required because AFTER_SUCCESS observers run with no active persistence context.
     * Returns null if the booking no longer exists.
     */
    private Loaded load(Long bookingId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Booking booking = Booking.findById(bookingId);
            if (booking == null) {
                return null;
            }
            MeetingType type = MeetingType.findById(booking.meetingTypeId);
            OwnerSettings owner = OwnerSettings.get();
            ZoneId zone = ZoneId.of(owner.timezone);
            List<AnswerLine> answers = buildAnswerLines(booking);
            return new Loaded(booking, type, owner, zone, answers);
        });
    }

    /**
     * Joins {@code BookingField.formFor(meetingTypeId)} (ordered by {@code position}) to
     * {@code booking.answers} by {@code fieldKey}, skipping blank/absent values. Must run inside the
     * {@code requiringNew()} transaction opened by {@link #load}.
     */
    private static List<AnswerLine> buildAnswerLines(Booking booking) {
        List<AnswerLine> lines = new ArrayList<>();
        Map<String, String> answers = booking.answers;
        if (answers == null || answers.isEmpty()) {
            return lines;
        }
        for (BookingField field : BookingField.formFor(booking.meetingTypeId)) {
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
