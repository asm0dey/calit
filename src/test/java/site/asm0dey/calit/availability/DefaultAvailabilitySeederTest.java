package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

class DefaultAvailabilitySeederTest {

    @Test
    void defaultsAreMondayToFridayNineToSixGlobal() {
        List<AvailabilityRule> rules = DefaultAvailabilitySeeder.weekdayDefaults();
        assertEquals(5, rules.size());
        for (AvailabilityRule r : rules) {
            assertEquals(LocalTime.of(9, 0), r.startTime);
            assertEquals(LocalTime.of(18, 0), r.endTime);
            assertNull(r.meetingTypeId, "default rules must be global");
            assertFalse(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(r.dayOfWeek), "weekdays only");
        }
        assertEquals(DayOfWeek.MONDAY, rules.getFirst().dayOfWeek);
        assertEquals(DayOfWeek.FRIDAY, rules.get(4).dayOfWeek);
    }
}
