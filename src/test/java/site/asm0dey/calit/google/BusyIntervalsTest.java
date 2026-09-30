package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BusyIntervalsTest {
    private static Instant t(String iso) {
        return Instant.parse(iso);
    }

    private static BusyInterval bi(String from, String to) {
        return new BusyInterval(t(from), t(to));
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertThat(BusyIntervals.merge(List.of())).isEmpty();
    }

    @Test
    void nonOverlappingIntervalsKeptSeparateAndSorted() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z"),
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z")
        ));

        assertThat(merged).hasSize(2);
        assertThat(merged).first().isEqualTo(bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"));
        assertThat(merged.get(1)).isEqualTo(bi("2026-06-08T11:00:00Z", "2026-06-08T12:00:00Z"));
    }

    @Test
    void overlappingIntervalsAreMerged() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:30:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")
        ));

        assertThat(merged).hasSize(1);
        assertThat(merged).first().isEqualTo(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"));
    }

    @Test
    void adjacentTouchingIntervalsAreMerged() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")
        ));

        assertThat(merged).hasSize(1);
        assertThat(merged).first().isEqualTo(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"));
    }

    @Test
    void fullyContainedIntervalIsAbsorbed() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z"),
                bi("2026-06-08T10:00:00Z", "2026-06-08T11:00:00Z")
        ));

        assertThat(merged).hasSize(1);
        assertThat(merged).first().isEqualTo(bi("2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z"));
    }

    @Test
    void outOfOrderMixIsSortedAndMergedCorrectly() {
        List<BusyInterval> merged = BusyIntervals.merge(List.of(
                bi("2026-06-08T14:00:00Z", "2026-06-08T15:00:00Z"),
                bi("2026-06-08T09:00:00Z", "2026-06-08T10:00:00Z"),
                bi("2026-06-08T09:30:00Z", "2026-06-08T11:00:00Z"),
                bi("2026-06-08T14:30:00Z", "2026-06-08T16:00:00Z")
        ));

        assertThat(merged).hasSize(2);
        assertThat(merged).first().isEqualTo(bi("2026-06-08T09:00:00Z", "2026-06-08T11:00:00Z"));
        assertThat(merged.get(1)).isEqualTo(bi("2026-06-08T14:00:00Z", "2026-06-08T16:00:00Z"));
    }
}
