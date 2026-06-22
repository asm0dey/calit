package site.asm0dey.calit.availability;

import site.asm0dey.calit.domain.AvailabilityRule;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAvailabilitySeederTest {

    @Test
    void defaultsAreMondayToFridayNineToSixGlobal() {
        List<AvailabilityRule> rules = DefaultAvailabilitySeeder.weekdayDefaults();
        assertEquals(5, rules.size());
        for (AvailabilityRule r : rules) {
            assertEquals(LocalTime.of(9, 0), r.startTime);
            assertEquals(LocalTime.of(18, 0), r.endTime);
            assertNull(r.meetingTypeId, "default rules must be global");
            assertTrue(r.dayOfWeek != DayOfWeek.SATURDAY && r.dayOfWeek != DayOfWeek.SUNDAY,
                    "weekdays only");
        }
        assertEquals(DayOfWeek.MONDAY, rules.get(0).dayOfWeek);
        assertEquals(DayOfWeek.FRIDAY, rules.get(4).dayOfWeek);
    }
}
