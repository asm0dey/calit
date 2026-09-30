package site.asm0dey.calit.availability;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

class DefaultAvailabilitySeederTest {
    @Test
    void defaultsAreMondayToFridayNineToSixGlobal() {
        List<AvailabilityRule> rules = DefaultAvailabilitySeeder.weekdayDefaults();
        assertThat(rules).hasSize(5);
        for (AvailabilityRule r : rules) {
            assertThat(r.startTime).isEqualTo(LocalTime.of(9, 0));
            assertThat(r.endTime).isEqualTo(LocalTime.of(18, 0));
            assertThat(r.meetingTypeId).as("default rules must be global").isNull();
            assertThat(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)).as("weekdays only").doesNotContain(r.dayOfWeek);
        }
        assertThat(rules.getFirst().dayOfWeek).isEqualTo(DayOfWeek.MONDAY);
        assertThat(rules.get(4).dayOfWeek).isEqualTo(DayOfWeek.FRIDAY);
    }
}
