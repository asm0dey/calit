package site.asm0dey.calit.caldav;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.BusyInterval;

class BusyExtractorTest {
    static final Instant FROM = Instant.parse("2026-11-02T00:00:00Z");
    static final Instant TO = Instant.parse("2026-11-09T00:00:00Z");
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    static String cal(String... events) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                + String.join("", events)
                + "END:VCALENDAR\r\n";
    }

    static String event(String... lines) {
        return "BEGIN:VEVENT\r\nDTSTAMP:20261001T000000Z\r\n"
                + Arrays.stream(lines).map(l -> l + "\r\n").collect(Collectors.joining())
                + "END:VEVENT\r\n";
    }

    static BusyInterval iv(String start, String end) {
        return new BusyInterval(Instant.parse(start), Instant.parse(end));
    }

    static List<BusyInterval> busy(String... data) {
        return BusyExtractor.busy(List.of(data), FROM, TO, BERLIN);
    }

    // Weekly from Monday 2026-10-19, three instances: 10-19, 10-26, 11-02. Only the last is in the window.
    static final String[] WEEKLY = {
            "UID:weekly",
            "DTSTART;TZID=Europe/Berlin:20261019T090000",
            "DTEND;TZID=Europe/Berlin:20261019T093000",
            "RRULE:FREQ=WEEKLY;COUNT=3"
    };

    @Test
    void weeklyRecurrenceExpands() {
        assertThat(busy(cal(event(WEEKLY)))).containsExactly(iv("2026-11-02T08:00:00Z", "2026-11-02T08:30:00Z"));
    }

    @Test
    void exdateRemovesInstance() {
        var lines = new ArrayList<>(List.of(WEEKLY));
        lines.set(3, "RRULE:FREQ=WEEKLY;COUNT=5");
        lines.add("EXDATE;TZID=Europe/Berlin:20261109T090000");
        // EXDATE in UTC on a TZID series must still match: 2026-11-02 09:00 Berlin = 08:00Z
        lines.add("EXDATE:20261102T080000Z");
        assertThat(busy(cal(event(lines.toArray(String[]::new))))).isEmpty();
    }

    @Test
    void recurrenceIdOverrideMovesInstance() {
        var override = event(
                "UID:weekly",
                "RECURRENCE-ID;TZID=Europe/Berlin:20261102T090000",
                "DTSTART;TZID=Europe/Berlin:20261104T140000",
                "DTEND;TZID=Europe/Berlin:20261104T150000"
        );
        assertThat(busy(cal(event(WEEKLY), override))).containsExactly(
                iv("2026-11-04T13:00:00Z", "2026-11-04T14:00:00Z")
        );
    }

    @Test
    void transparentIgnored() {
        assertThat(
                busy(cal(event("UID:t", "DTSTART:20261103T100000Z", "DTEND:20261103T110000Z", "TRANSP:TRANSPARENT")))
        )
            .isEmpty();
    }

    @Test
    void cancelledIgnored() {
        assertThat(busy(cal(event("UID:c", "DTSTART:20261103T100000Z", "DTEND:20261103T110000Z", "STATUS:CANCELLED"))))
            .isEmpty();
    }

    @Test
    void allDayBlocksOwnerDay() {
        assertThat(busy(cal(event("UID:a", "DTSTART;VALUE=DATE:20261103", "DTEND;VALUE=DATE:20261104"))))
            .containsExactly(iv("2026-11-02T23:00:00Z", "2026-11-03T23:00:00Z"));
    }

    @Test
    void separateObjectsAreMerged() {
        assertThat(
                busy(
                        cal(event("UID:x", "DTSTART:20261103T100000Z", "DTEND:20261103T110000Z")),
                        cal(event("UID:y", "DTSTART:20261103T103000Z", "DTEND:20261103T120000Z")),
                        "not ical at all"
                )
        )
            .containsExactly(iv("2026-11-03T10:00:00Z", "2026-11-03T12:00:00Z"));
    }
}
