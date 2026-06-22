package site.asm0dey.calit.email;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Hand-rolled RFC 5545 VCALENDAR/VEVENT builder for a single booking.
 * Emitted as an .ics attachment on every app-sent email so the recipient
 * can add the meeting to any calendar (independent of Google).
 */
public final class IcsBuilder {

    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsBuilder() {
    }

    /**
     * @param uid          stable unique id (we pass the booking's manageToken so updates match)
     * @param summary      event title (the meeting type name)
     * @param location     Meet link or locationDetail; null/blank emits no LOCATION line
     * @param organizerEmail the owner's email
     * @param start        meeting start (UTC instant)
     * @param end          meeting end (UTC instant)
     */
    public static String build(String uid, String summary, String location,
                               String organizerEmail, Instant start, Instant end) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//calit//EN\r\n");
        sb.append("METHOD:REQUEST\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(escape(uid)).append("\r\n");
        sb.append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n");
        sb.append("DTSTART:").append(ICS_UTC.format(start)).append("\r\n");
        sb.append("DTEND:").append(ICS_UTC.format(end)).append("\r\n");
        sb.append("SUMMARY:").append(escape(summary)).append("\r\n");
        if (location != null && !location.isBlank()) {
            sb.append("LOCATION:").append(escape(location)).append("\r\n");
        }
        sb.append("ORGANIZER:mailto:").append(escape(organizerEmail)).append("\r\n");
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    /** RFC 5545 text escaping for any interpolated value; also strips CR so no value can inject a line. */
    private static String escape(String v) {
        return v.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}
