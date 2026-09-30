package site.asm0dey.calit.web;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

class WeekRowTest {
    private static AvailabilityRule rule(DayOfWeek day, String start, String end) {
        AvailabilityRule r = new AvailabilityRule();
        r.dayOfWeek = day;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        return r;
    }

    @Test
    void buildsSevenRowsInIsoOrderEvenWhenEmpty() {
        List<WeekRow> rows = WeekRow.fromRules(List.of());
        assertThat(rows).hasSize(7);
        assertThat(rows.getFirst().day()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(rows.get(6).day()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(rows.getFirst().frames()).isEmpty();
    }

    @Test
    void groupsFramesByDayAndSortsByStartTime() {
        List<WeekRow> rows = WeekRow.fromRules(List.of(
                rule(DayOfWeek.MONDAY, "13:00", "17:00"),
                rule(DayOfWeek.MONDAY, "09:00", "12:00"),
                rule(DayOfWeek.WEDNESDAY, "10:00", "11:00")
        ));

        WeekRow monday = rows.getFirst();
        assertThat(monday.frames()).hasSize(2);
        // sorted
        assertThat(monday.frames().getFirst().startTime).isEqualTo(LocalTime.parse("09:00"));
        assertThat(monday.frames().get(1).startTime).isEqualTo(LocalTime.parse("13:00"));
        // TUESDAY
        assertThat(rows.get(1).frames()).isEmpty();
        // WEDNESDAY
        assertThat(rows.get(2).frames()).hasSize(1);
    }
}
