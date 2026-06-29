package site.asm0dey.calit.web;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import site.asm0dey.calit.domain.AvailabilityRule;

/**
 * One weekday's worth of the weekly-schedule grid: the day plus its time frames,
 * sorted by start time. {@link #fromRules} always yields all seven days in ISO order
 * (Monday first), each with an (possibly empty) frame list, so the template can render
 * a fixed seven-row grid.
 */
public record WeekRow(DayOfWeek day, List<AvailabilityRule> frames) {

    public static List<WeekRow> fromRules(List<AvailabilityRule> rules) {
        Map<DayOfWeek, List<AvailabilityRule>> byDay = rules.stream().collect(Collectors.groupingBy(r -> r.dayOfWeek));
        List<WeekRow> rows = new ArrayList<>(7);
        for (DayOfWeek d : DayOfWeek.values()) {
            List<AvailabilityRule> frames = byDay.getOrDefault(d, List.of()).stream()
                    .sorted(Comparator.comparing(r -> r.startTime))
                    .toList();
            rows.add(new WeekRow(d, frames));
        }
        return rows;
    }
}
