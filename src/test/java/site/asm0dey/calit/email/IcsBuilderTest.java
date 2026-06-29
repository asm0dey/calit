package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IcsBuilderTest {

    @Test
    void buildsVeventWithStartEndSummaryLocationOrganizerUid() {
        String ics = IcsBuilder.build(IcsEvent.builder()
                .uid("tok-123")
                .summary("Discovery Call")
                .location("https://meet.google.com/abc-defg-hij")
                .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
                .attendee(new IcsBuilder.Party("Invitee Name", "invitee@example.com"))
                .start(Instant.parse("2026-06-08T09:00:00Z"))
                .end(Instant.parse("2026-06-08T09:30:00Z"))
                .build());

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
        String ics = IcsBuilder.build(IcsEvent.builder()
                .uid("tok-x")
                .summary("Phone Call")
                .location(null)
                .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
                .attendee(new IcsBuilder.Party("Invitee Name", "invitee@example.com"))
                .start(Instant.parse("2026-06-08T09:00:00Z"))
                .end(Instant.parse("2026-06-08T09:30:00Z"))
                .build());
        assertTrue(ics.contains("BEGIN:VEVENT"));
        assertTrue(!ics.contains("LOCATION:"), "no LOCATION line when location is null/blank");
    }

    @Test
    void requestHasAttendeeAndOrganizer() {
        String ics = IcsBuilder.build(IcsEvent.builder()
                .uid("uid-1")
                .summary("Intro call")
                .location("https://meet.google.com/abc-defg-hij")
                .organizer(new IcsBuilder.Party("Olivia Owner", "owner@example.com"))
                .attendee(new IcsBuilder.Party("Sam Invitee", "sam@example.com"))
                .start(Instant.parse("2026-07-01T09:00:00Z"))
                .end(Instant.parse("2026-07-01T09:30:00Z"))
                .build());

        assertTrue(ics.contains("METHOD:REQUEST"), "must be an iTIP REQUEST");
        assertTrue(
                ics.contains("ATTENDEE;") && ics.contains("mailto:sam@example.com"),
                "invitee must appear as ATTENDEE (what Gmail needs to render the card)");
        assertTrue(
                ics.contains("ORGANIZER;CN=\"Olivia Owner\":mailto:owner@example.com"),
                "owner must be the ORGANIZER with a CN");
        assertTrue(ics.contains("SEQUENCE:0"), "REQUEST needs a SEQUENCE");
        assertTrue(ics.contains("STATUS:CONFIRMED"), "event needs a STATUS");
        assertTrue(ics.contains("BEGIN:VEVENT\r\n"), "CRLF line endings preserved");
    }

    @Test
    void cancelMethodEmitsCancelStatusAndSequence() {
        String ics = IcsBuilder.build(IcsEvent.builder()
                .uid("tok-9")
                .summary("Discovery Call")
                .location(null)
                .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
                .attendee(new IcsBuilder.Party("guest@example.com", "guest@example.com"))
                .start(Instant.parse("2026-06-08T09:00:00Z"))
                .end(Instant.parse("2026-06-08T09:30:00Z"))
                .method(IcsMethod.CANCEL)
                .sequence(3)
                .attendeeRsvp(true)
                .build());

        assertTrue(ics.contains("METHOD:CANCEL"), "cancel must be an iTIP CANCEL");
        assertTrue(ics.contains("STATUS:CANCELLED"), "cancelled event status");
        assertTrue(ics.contains("SEQUENCE:3"), "sequence carried through");
        assertTrue(ics.contains("UID:tok-9"), "same UID so the client matches the prior event");
        assertTrue(ics.contains("mailto:guest@example.com"), "guest is the attendee");
    }

    @Test
    void requestOverloadWithSequenceEmitsRequestAndConfirmed() {
        String ics = IcsBuilder.build(IcsEvent.builder()
                .uid("tok-9")
                .summary("Discovery Call")
                .location("https://meet.google.com/abc")
                .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
                .attendee(new IcsBuilder.Party("guest@example.com", "guest@example.com"))
                .start(Instant.parse("2026-06-08T09:00:00Z"))
                .end(Instant.parse("2026-06-08T09:30:00Z"))
                .method(IcsMethod.REQUEST)
                .sequence(1)
                .attendeeRsvp(true)
                .build());

        assertTrue(ics.contains("METHOD:REQUEST"));
        assertTrue(ics.contains("STATUS:CONFIRMED"));
        assertTrue(ics.contains("SEQUENCE:1"));
    }

    @Test
    void attendeeRsvpFalseEmitsRsvpFalse() {
        String ics = IcsBuilder.build(IcsEvent.builder()
                .uid("tok-g")
                .summary("Discovery Call")
                .location(null)
                .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
                .attendee(new IcsBuilder.Party("guest@example.com", "guest@example.com"))
                .start(Instant.parse("2026-06-08T09:00:00Z"))
                .end(Instant.parse("2026-06-08T09:30:00Z"))
                .method(IcsMethod.REQUEST)
                .sequence(0)
                .attendeeRsvp(false)
                .build());
        assertTrue(ics.contains("RSVP=FALSE"), "guest invite suppresses the calendar RSVP buttons");
        assertFalse(ics.contains("RSVP=TRUE"));
    }
}
