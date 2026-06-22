package site.asm0dey.calit.email;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IcsBuilderTest {

    @Test
    void buildsVeventWithStartEndSummaryLocationOrganizerUid() {
        String ics = IcsBuilder.build(
                "tok-123",
                "Discovery Call",
                "https://meet.google.com/abc-defg-hij",
                "owner@example.com", "Owner Name",
                "invitee@example.com", "Invitee Name",
                Instant.parse("2026-06-08T09:00:00Z"),
                Instant.parse("2026-06-08T09:30:00Z"));

        assertTrue(ics.startsWith("BEGIN:VCALENDAR"), "must be a VCALENDAR");
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(ics.contains("END:VEVENT"));
        assertTrue(ics.contains("END:VCALENDAR"));
        assertTrue(ics.contains("UID:tok-123"), "uid drives calendar de-dup/updates");
        assertTrue(ics.contains("SUMMARY:Discovery Call"));
        assertTrue(ics.contains("LOCATION:https://meet.google.com/abc-defg-hij"));
        assertTrue(ics.contains("ORGANIZER;CN=\"Owner Name\":mailto:owner@example.com"));
        assertTrue(ics.contains("DTSTART:20260608T090000Z"), "start in UTC basic format");
        assertTrue(ics.contains("DTEND:20260608T093000Z"), "end in UTC basic format");
    }

    @Test
    void omitsLocationLineWhenNull() {
        String ics = IcsBuilder.build(
                "tok-x", "Phone Call", null, "owner@example.com", "Owner Name",
                "invitee@example.com", "Invitee Name",
                Instant.parse("2026-06-08T09:00:00Z"),
                Instant.parse("2026-06-08T09:30:00Z"));
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(!ics.contains("LOCATION:"), "no LOCATION line when location is null/blank");
    }

    @Test
    void requestHasAttendeeAndOrganizer() {
        String ics = IcsBuilder.build(
                "uid-1", "Intro call", "https://meet.google.com/abc-defg-hij",
                "owner@example.com", "Olivia Owner",
                "sam@example.com", "Sam Invitee",
                Instant.parse("2026-07-01T09:00:00Z"), Instant.parse("2026-07-01T09:30:00Z"));

        assertTrue(ics.contains("METHOD:REQUEST"), "must be an iTIP REQUEST");
        assertTrue(ics.contains("ATTENDEE;") && ics.contains("mailto:sam@example.com"),
                "invitee must appear as ATTENDEE (what Gmail needs to render the card)");
        assertTrue(ics.contains("ORGANIZER;CN=\"Olivia Owner\":mailto:owner@example.com"),
                "owner must be the ORGANIZER with a CN");
        assertTrue(ics.contains("SEQUENCE:0"), "REQUEST needs a SEQUENCE");
        assertTrue(ics.contains("STATUS:CONFIRMED"), "event needs a STATUS");
        assertTrue(ics.contains("BEGIN:VEVENT\r\n"), "CRLF line endings preserved");
    }
}
