package site.asm0dey.calit.google;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusyIntervalsTest {

    private static Instant t(String iso) {
        return Instant.parse(iso);
    }

    private static BusyInterval bi(String from, String to) {
        return new BusyInterval(t(from), t(to));
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertTrue(BusyIntervals.merge(List.of()).isEmpty());
    }

    @Test
    void nonOverlappingIntervalsKeptSeparateAndSorted() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z"),
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z")));

        assertEquals(2, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"), merged.get(0));
        assertEquals(bi("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z"), merged.get(1));
    }

    @Test
    void overlappingIntervalsAreMerged() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:30:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")));

        assertEquals(1, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"), merged.get(0));
    }

    @Test
    void adjacentTouchingIntervalsAreMerged() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")));

        assertEquals(1, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"), merged.get(0));
    }

    @Test
    void fullyContainedIntervalIsAbsorbed() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")));

        assertEquals(1, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z"), merged.get(0));
    }

    @Test
    void outOfOrderMixIsSortedAndMergedCorrectly() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T14:00:00Z", "2026-06-08T15:00:00Z"),
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"),
                bi("2026-06-08T09:30:00Z", "2026-06-08T11:00:00Z"),
                bi("2026-06-08T14:30:00Z", "2026-06-08T16:00:00Z")));

        assertEquals(2, merged.size());
        assertEquals(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"), merged.get(0));
        assertEquals(bi("2026-06-08T14:00:00Z", "2026-06-08T16:00:00Z"), merged.get(1));
    }
}
