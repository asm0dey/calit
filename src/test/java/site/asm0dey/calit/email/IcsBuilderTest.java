package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class IcsBuilderTest {
    @Test
    void buildsVeventWithStartEndSummaryLocationOrganizerUid() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
            .uid("tok-123")
            .summary("Discovery Call")
            .location("https://meet.google.com/abc-defg-hij")
            .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
            .attendee(new IcsBuilder.Party("Invitee Name", "invitee@example.com"))
            .start(Instant.parse("2026-06-08T09:00:00Z"))
            .end(Instant.parse("2026-06-08T09:30:00Z"))
            .build()
        );

        assertThat(ics).as("must be a VCALENDAR").startsWith("BEGIN:VCALENDAR");
        assertThat(ics).contains("BEGIN:VEVENT").contains("END:VEVENT").contains("END:VCALENDAR");
        assertThat(ics).as("uid drives calendar de-dup/updates").contains("UID:tok-123");
        assertThat(ics)
            .contains("SUMMARY:Discovery Call")
            .contains("LOCATION:https://meet.google.com/abc-defg-hij")
            .contains("ORGANIZER;CN=\"Owner Name\":mailto:owner@example.com");
        assertThat(ics).as("start in UTC basic format").contains("DTSTART:20260608T090000Z");
        assertThat(ics).as("end in UTC basic format").contains("DTEND:20260608T093000Z");
    }

    @Test
    void omitsLocationLineWhenNull() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
            .uid("tok-x")
            .summary("Phone Call")
            .location(null)
            .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
            .attendee(new IcsBuilder.Party("Invitee Name", "invitee@example.com"))
            .start(Instant.parse("2026-06-08T09:00:00Z"))
            .end(Instant.parse("2026-06-08T09:30:00Z"))
            .build()
        );
        assertThat(ics).contains("BEGIN:VEVENT");
        assertThat(ics).as("no LOCATION line when location is null/blank").doesNotContain("LOCATION:");
    }

    @Test
    void requestHasAttendeeAndOrganizer() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
            .uid("uid-1")
            .summary("Intro call")
            .location("https://meet.google.com/abc-defg-hij")
            .organizer(new IcsBuilder.Party("Olivia Owner", "owner@example.com"))
            .attendee(new IcsBuilder.Party("Sam Invitee", "sam@example.com"))
            .start(Instant.parse("2026-07-01T09:00:00Z"))
            .end(Instant.parse("2026-07-01T09:30:00Z"))
            .build()
        );

        assertThat(ics).as("must be an iTIP REQUEST").contains("METHOD:REQUEST");
        assertThat(ics.contains("ATTENDEE;") && ics.contains("mailto:sam@example.com"))
            .as("invitee must appear as ATTENDEE (what Gmail needs to render the card)")
            .isTrue();
        assertThat(ics)
            .as("owner must be the ORGANIZER with a CN")
            .contains("ORGANIZER;CN=\"Olivia Owner\":mailto:owner@example.com");
        assertThat(ics).as("REQUEST needs a SEQUENCE").contains("SEQUENCE:0");
        assertThat(ics).as("event needs a STATUS").contains("STATUS:CONFIRMED");
        assertThat(ics).as("CRLF line endings preserved").contains("BEGIN:VEVENT\r\n");
    }

    @Test
    void cancelMethodEmitsCancelStatusAndSequence() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
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
            .build()
        );

        assertThat(ics).as("cancel must be an iTIP CANCEL").contains("METHOD:CANCEL");
        assertThat(ics).as("cancelled event status").contains("STATUS:CANCELLED");
        assertThat(ics).as("sequence carried through").contains("SEQUENCE:3");
        assertThat(ics).as("same UID so the client matches the prior event").contains("UID:tok-9");
        assertThat(ics).as("guest is the attendee").contains("mailto:guest@example.com");
    }

    @Test
    void requestOverloadWithSequenceEmitsRequestAndConfirmed() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
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
            .build()
        );

        assertThat(ics).contains("METHOD:REQUEST").contains("STATUS:CONFIRMED").contains("SEQUENCE:1");
    }

    @Test
    void attendeeRsvpFalseEmitsRsvpFalse() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
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
            .build()
        );
        assertThat(ics).as("guest invite suppresses the calendar RSVP buttons").contains("RSVP=FALSE");
        assertThat(ics).doesNotContain("RSVP=TRUE");
    }

    @Test
    void emitsDescriptionLineWhenPresent() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
            .uid("tok-d")
            .summary("Roadmap sync")
            .description("Q3 planning agenda")
            .location(null)
            .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
            .attendee(new IcsBuilder.Party("Invitee", "invitee@example.com"))
            .start(Instant.parse("2026-06-08T09:00:00Z"))
            .end(Instant.parse("2026-06-08T09:30:00Z"))
            .build()
        );
        assertThat(ics).contains("DESCRIPTION:Q3 planning agenda");
    }

    @Test
    void omitsDescriptionLineWhenNullOrBlank() {
        String ics = IcsBuilder.build(IcsEvent
            .builder()
            .uid("tok-d2")
            .summary("Roadmap sync")
            .description("   ")
            .location(null)
            .organizer(new IcsBuilder.Party("Owner Name", "owner@example.com"))
            .attendee(new IcsBuilder.Party("Invitee", "invitee@example.com"))
            .start(Instant.parse("2026-06-08T09:00:00Z"))
            .end(Instant.parse("2026-06-08T09:30:00Z"))
            .build()
        );
        assertThat(ics).doesNotContain("DESCRIPTION:");
    }
}
