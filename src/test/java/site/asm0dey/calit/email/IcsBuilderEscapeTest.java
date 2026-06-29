package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IcsBuilderEscapeTest {

    @Test
    void organizerAndUidCannotInjectNewIcsLines() {
        String ics = IcsBuilder.build(IcsEvent.builder()
                .uid("uid\r\nX-EVIL:1")
                .summary("Meeting")
                .location(null)
                .organizer(new IcsBuilder.Party("Organizer Name", "organizer@x.com\r\nATTENDEE:mailto:victim@y.com"))
                .attendee(new IcsBuilder.Party("Attendee Name", "attendee@example.com"))
                .start(Instant.parse("2099-01-01T10:00:00Z"))
                .end(Instant.parse("2099-01-01T10:30:00Z"))
                .build());

        // RFC 5545 line folding is on CRLF. The attacker payloads must never become their own
        // physical line: the CR is stripped and the LF is escaped to the literal text "\n", so the
        // injected text stays trapped inside the UID / ORGANIZER value rather than starting a new
        // property line. Assert on physical lines (split on the real CRLF terminator) — not on a raw
        // substring, since the harmless escaped literal "...\nX-EVIL:1" legitimately remains present.
        boolean[] noInjectedLine = {true, true};
        for (String line : ics.split("\r\n")) {
            if (line.equals("X-EVIL:1") || line.startsWith("X-EVIL:")) {
                noInjectedLine[0] = false;
            }
            if (line.equals("ATTENDEE:mailto:victim@y.com") || line.startsWith("ATTENDEE:")) {
                noInjectedLine[1] = false;
            }
        }
        assertTrue(noInjectedLine[0], "UID must not inject a new property line");
        assertTrue(noInjectedLine[1], "ORGANIZER must not inject a new property line");

        // And no raw CR/LF survives anywhere inside a value to fold/inject a line.
        assertFalse(
                Arrays.asList(ics.split("\r\n")).stream().anyMatch(l -> l.contains("\n") || l.contains("\r")),
                "no raw CR/LF may survive inside any property value");
    }
}
