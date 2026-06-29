package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntervalTest {

    private static Interval iv(String start, String end) {
        return new Interval(Instant.parse(start), Instant.parse(end));
    }

    @Test
    void overlappingIntervalsReportOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T09:30:00Z", "2026-06-08T10:30:00Z");
        assertTrue(a.overlaps(b));
        assertTrue(b.overlaps(a));
    }

    @Test
    void touchingBoundariesDoNotOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z");
        assertFalse(a.overlaps(b));
        assertFalse(b.overlaps(a));
    }

    @Test
    void disjointIntervalsDoNotOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z");
        assertFalse(a.overlaps(b));
    }

    @Test
    void containedIntervalOverlaps() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z");
        Interval b = iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z");
        assertTrue(a.overlaps(b));
    }

    @Test
    void overlapsAnyMatchesAtLeastOne() {
        Interval slot = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        List<Interval> busy = List.of(
                iv("2026-06-08T07:00:00Z", "2026-06-08T08:00:00Z"), iv("2026-06-08T09:30:00Z", "2026-06-08T09:45:00Z"));
        assertTrue(slot.overlapsAny(busy));
    }

    @Test
    void overlapsAnyFalseWhenAllDisjoint() {
        Interval slot = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        List<Interval> busy = List.of(
                iv("2026-06-08T07:00:00Z", "2026-06-08T08:00:00Z"), iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z"));
        assertFalse(slot.overlapsAny(busy));
    }
}
