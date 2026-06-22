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
                "owner@example.com",
                Instant.parse("2026-06-08T09:00:00Z"),
                Instant.parse("2026-06-08T09:30:00Z"));

        assertTrue(ics.startsWith("BEGIN:VCALENDAR"), "must be a VCALENDAR");
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(ics.contains("END:VEVENT"));
        assertTrue(ics.contains("END:VCALENDAR"));
        assertTrue(ics.contains("UID:tok-123"), "uid drives calendar de-dup/updates");
        assertTrue(ics.contains("SUMMARY:Discovery Call"));
        assertTrue(ics.contains("LOCATION:https://meet.google.com/abc-defg-hij"));
        assertTrue(ics.contains("ORGANIZER:mailto:owner@example.com"));
        assertTrue(ics.contains("DTSTART:20260608T090000Z"), "start in UTC basic format");
        assertTrue(ics.contains("DTEND:20260608T093000Z"), "end in UTC basic format");
    }

    @Test
    void omitsLocationLineWhenNull() {
        String ics = IcsBuilder.build(
                "tok-x", "Phone Call", null, "owner@example.com",
                Instant.parse("2026-06-08T09:00:00Z"),
                Instant.parse("2026-06-08T09:30:00Z"));
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(!ics.contains("LOCATION:"), "no LOCATION line when location is null/blank");
    }
}
