package site.asm0dey.calit.web;

import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(DayOfWeek.MONDAY, rows.get(0).day());
        assertEquals(DayOfWeek.SUNDAY, rows.get(6).day());
        assertTrue(rows.get(0).frames().isEmpty());
    }

    @Test
    void groupsFramesByDayAndSortsByStartTime() {
        List<WeekRow> rows = WeekRow.fromRules(List.of(
                rule(DayOfWeek.MONDAY, "13:00", "17:00"),
                rule(DayOfWeek.MONDAY, "09:00", "12:00"),
                rule(DayOfWeek.WEDNESDAY, "10:00", "11:00")));

        WeekRow monday = rows.get(0);
        assertEquals(2, monday.frames().size());
        assertEquals(LocalTime.parse("09:00"), monday.frames().get(0).startTime); // sorted
        assertEquals(LocalTime.parse("13:00"), monday.frames().get(1).startTime);

        assertTrue(rows.get(1).frames().isEmpty()); // TUESDAY
        assertEquals(1, rows.get(2).frames().size()); // WEDNESDAY
    }
}
