package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class IntervalTest {
    private static Interval iv(String start, String end) {
        return new Interval(Instant.parse(start), Instant.parse(end));
    }

    @Test
    void overlappingIntervalsReportOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T09:30:00Z", "2026-06-08T10:30:00Z");
        assertThat(a.overlaps(b)).isTrue();
        assertThat(b.overlaps(a)).isTrue();
    }

    @Test
    void touchingBoundariesDoNotOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z");
        assertThat(a.overlaps(b)).isFalse();
        assertThat(b.overlaps(a)).isFalse();
    }

    @Test
    void disjointIntervalsDoNotOverlap() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        Interval b = iv("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z");
        assertThat(a.overlaps(b)).isFalse();
    }

    @Test
    void containedIntervalOverlaps() {
        Interval a = iv("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z");
        Interval b = iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z");
        assertThat(a.overlaps(b)).isTrue();
    }

    @Test
    void overlapsAnyMatchesAtLeastOne() {
        Interval slot = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        List<Interval> busy = List.of(
                iv("2026-06-08T07:00:00Z", "2026-06-08T08:00:00Z"),
                iv("2026-06-08T09:30:00Z", "2026-06-08T09:45:00Z")
        );
        assertThat(slot.overlapsAny(busy)).isTrue();
    }

    @Test
    void overlapsAnyFalseWhenAllDisjoint() {
        Interval slot = iv("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z");
        List<Interval> busy = List.of(
                iv("2026-06-08T07:00:00Z", "2026-06-08T08:00:00Z"),
                iv("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")
        );
        assertThat(slot.overlapsAny(busy)).isFalse();
    }
}
