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

    /**
     * Seven rows, one per weekday, each seeded with exactly one blank (unpersisted) frame — the
     * create-meeting-type form's initial grid state. Without JS, that seeded row IS the editor, so
     * every day must offer one immediately; {@link #fromRules} can't produce this shape since it
     * only surfaces days that already have rules. The frame's {@code startTime}/{@code endTime}
     * are left null; the grid template renders that as an empty input value, never the text "null".
     */
    public static List<WeekRow> blank() {
        List<WeekRow> rows = new ArrayList<>(7);
        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule blankFrame = new AvailabilityRule();
            blankFrame.dayOfWeek = d;
            rows.add(new WeekRow(d, List.of(blankFrame)));
        }
        return rows;
    }
}
