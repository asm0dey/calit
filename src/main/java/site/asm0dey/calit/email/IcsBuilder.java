package site.asm0dey.calit.email;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Hand-rolled RFC 5545 VCALENDAR/VEVENT builder for a single booking.
 * Emitted as an .ics attachment on every app-sent email so the recipient
 * can add the meeting to any calendar (independent of Google).
 *
 * <p>METHOD:REQUEST means this is an iTIP invitation. Such a request MUST carry at least one
 * ATTENDEE — Gmail shows "Unable to load event" for a REQUEST with none. The owner is the
 * ORGANIZER and the invitee is the ATTENDEE; both carry a CN so each side's mail client
 * matches the recipient and renders the event card.
 */
public final class IcsBuilder {

    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsBuilder() {}

    /** A named calendar participant: display name (CN) + email (mailto). */
    public record Party(String name, String email) {}

    /**
     * Renders a single booking as an RFC 5545 VCALENDAR/VEVENT string. All event properties are
     * sourced from the supplied {@link IcsEvent}; use {@link IcsEvent#builder()} to construct one.
     *
     * @param e the event descriptor; method defaults to "REQUEST", sequence to 0, attendeeRsvp to true
     */
    public static String build(IcsEvent e) {
        var cancel = e.method() == IcsMethod.CANCEL;
        var sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//calit//EN\r\n");
        sb.append("METHOD:").append(e.method().name()).append("\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(escape(e.uid())).append("\r\n");
        sb.append("SEQUENCE:").append(e.sequence()).append("\r\n");
        sb.append("STATUS:").append(cancel ? "CANCELLED" : "CONFIRMED").append("\r\n");
        sb.append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n");
        sb.append("DTSTART:").append(ICS_UTC.format(e.start())).append("\r\n");
        sb.append("DTEND:").append(ICS_UTC.format(e.end())).append("\r\n");
        sb.append("SUMMARY:").append(escape(e.summary())).append("\r\n");
        if (e.location() != null && !e.location().isBlank()) {
            sb.append("LOCATION:").append(escape(e.location())).append("\r\n");
        }
        sb.append("ORGANIZER;CN=")
                .append(cn(e.organizer().name()))
                .append(":mailto:")
                .append(escape(e.organizer().email()))
                .append("\r\n");
        sb.append("ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=")
                .append(e.attendeeRsvp() ? "TRUE" : "FALSE")
                .append(";CN=")
                .append(cn(e.attendee().name()))
                .append(":mailto:")
                .append(escape(e.attendee().email()))
                .append("\r\n");
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    /** RFC 5545 text escaping for any interpolated property value; strips CR so no value injects a line. */
    private static String escape(String v) {
        return v.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    /**
     * Quoted CN param value (RFC 5545 §3.2). Double-quoting lets a name contain ';' or ','
     * without escaping; we only need to strip CR/LF and replace any inner double-quote.
     */
    private static String cn(String v) {
        var safe = (v == null ? "" : v).replace("\r", "").replace("\n", " ").replace("\"", "'");
        return "\"" + safe + "\"";
    }
}
