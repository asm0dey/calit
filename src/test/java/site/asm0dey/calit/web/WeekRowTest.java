package site.asm0dey.calit.web;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assertEquals(7, rows.size());
        assertEquals(DayOfWeek.MONDAY, rows.getFirst().day());
        assertEquals(DayOfWeek.SUNDAY, rows.get(6).day());
        assertTrue(rows.getFirst().frames().isEmpty());
    }

    @Test
    void groupsFramesByDayAndSortsByStartTime() {
        List<WeekRow> rows = WeekRow.fromRules(List.of(
                rule(DayOfWeek.MONDAY, "13:00", "17:00"),
                rule(DayOfWeek.MONDAY, "09:00", "12:00"),
                rule(DayOfWeek.WEDNESDAY, "10:00", "11:00")
        ));

        WeekRow monday = rows.getFirst();
        assertEquals(2, monday.frames().size());
        // sorted
        assertEquals(LocalTime.parse("09:00"), monday.frames().getFirst().startTime);
        assertEquals(LocalTime.parse("13:00"), monday.frames().get(1).startTime);
        // TUESDAY
        assertTrue(rows.get(1).frames().isEmpty());
        // WEDNESDAY
        assertEquals(1, rows.get(2).frames().size());
    }
}
