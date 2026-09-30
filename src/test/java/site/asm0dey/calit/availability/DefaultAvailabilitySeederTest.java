package site.asm0dey.calit.availability;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;

class DefaultAvailabilitySeederTest {
    @Test
    void defaultsAreMondayToFridayNineToSixGlobal() {
        List<AvailabilityRule> rules = DefaultAvailabilitySeeder.weekdayDefaults();
        assertThat(rules)
            .hasSize(5)
            .allSatisfy(r -> {
                assertThat(r.startTime).isEqualTo(LocalTime.of(9, 0));
                assertThat(r.endTime).isEqualTo(LocalTime.of(18, 0));
                assertThat(r.meetingTypeId).as("default rules must be global").isNull();
            })
            .extracting(r -> r.dayOfWeek)
            .containsExactly(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY
            );
    }
}
