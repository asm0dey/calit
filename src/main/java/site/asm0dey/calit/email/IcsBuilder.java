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

    private IcsBuilder() {
    }

    /**
     * @param uid            stable unique id (we pass the booking's manageToken so updates match)
     * @param summary        event title (the meeting type name)
     * @param location       Meet link or locationDetail; null/blank emits no LOCATION line
     * @param organizerEmail the owner's email
     * @param organizerName  the owner's display name (CN on ORGANIZER)
     * @param attendeeEmail  the invitee's email (ATTENDEE — required for a valid REQUEST)
     * @param attendeeName   the invitee's display name (CN on ATTENDEE)
     * @param start          meeting start (UTC instant)
     * @param end            meeting end (UTC instant)
     */
    public static String build(String uid, String summary, String location,
                               String organizerEmail, String organizerName,
                               String attendeeEmail, String attendeeName,
                               Instant start, Instant end) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//calit//EN\r\n");
        sb.append("METHOD:REQUEST\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(escape(uid)).append("\r\n");
        sb.append("SEQUENCE:0\r\n");
        sb.append("STATUS:CONFIRMED\r\n");
        sb.append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n");
        sb.append("DTSTART:").append(ICS_UTC.format(start)).append("\r\n");
        sb.append("DTEND:").append(ICS_UTC.format(end)).append("\r\n");
        sb.append("SUMMARY:").append(escape(summary)).append("\r\n");
        if (location != null && !location.isBlank()) {
            sb.append("LOCATION:").append(escape(location)).append("\r\n");
        }
        sb.append("ORGANIZER;CN=").append(cn(organizerName))
                .append(":mailto:").append(escape(organizerEmail)).append("\r\n");
        sb.append("ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=")
                .append(cn(attendeeName)).append(":mailto:").append(escape(attendeeEmail)).append("\r\n");
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
        String safe = (v == null ? "" : v).replace("\r", "").replace("\n", " ").replace("\"", "'");
        return "\"" + safe + "\"";
    }
}
