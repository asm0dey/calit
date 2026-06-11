package com.calit.availability;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.DateOverride;
import com.calit.domain.DateOverrideWindow;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class SlotService {

    /**
     * Raw bookable windows derived from work hours only.
     * Conflict/busy/buffer subtraction is applied by Plan 3 on top of this output.
     *
     * <p>All availability data (weekly rules, date overrides, and override windows) is batch-loaded
     * up front in exactly three queries, independent of the horizon length, then resolved per day in
     * memory. This avoids an N+1 where each day in {@code [from, to]} would otherwise re-query rules
     * and overrides (~3 queries/day, ~180 over a 60-day horizon).
     */
    public List<TimeSlot> generateRawSlots(MeetingType type, LocalDate from, LocalDate to) {
        OwnerSettings settings = OwnerSettings.forOwner(type.ownerId);
        if (settings == null) {
            throw new IllegalStateException(
                    "Owner settings not configured for owner " + type.ownerId
                    + "; set them via /me/settings before generating slots.");
        }
        ZoneId zone = ZoneId.of(settings.timezone);
        Availability availability = loadAvailability(type, from, to);
        List<TimeSlot> slots = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (Window window : availability.windowsFor(date)) {
                int step = type.effectiveSlotIntervalMinutes();
                int duration = type.durationMinutes;
                // Work in minute-of-day to avoid LocalTime.plusMinutes() wrapping past midnight,
                // which (e.g. a window ending 23:30 with a 30-min duration) would loop forever.
                int startMin = window.start().toSecondOfDay() / 60;
                int endMin = window.end().toSecondOfDay() / 60;
                for (int s = startMin; s + duration <= endMin; s += step) {
                    LocalTime start = LocalTime.ofSecondOfDay(s * 60L);
                    LocalTime end = LocalTime.ofSecondOfDay((s + duration) * 60L);
                    slots.add(new TimeSlot(
                            date.atTime(start).atZone(zone),
                            date.atTime(end).atZone(zone)));
                }
            }
        }
        return slots;
    }

    /** A bookable [start, end) time-of-day window for one day, from either an override or a weekly rule. */
    record Window(LocalTime start, LocalTime end) {}

    /**
     * Pre-loaded availability for an owner+type over a date range, resolved per day in memory.
     * Mirrors {@link DateOverride#resolve} / the old {@code windowsFor}/{@code rulesFor} semantics
     * exactly, just sourced from maps populated by three up-front queries instead of per-day reads.
     */
    private record Availability(
            Map<LocalDate, DateOverride> typedOverrides,
            Map<LocalDate, DateOverride> globalOverrides,
            Map<DayOfWeek, List<AvailabilityRule>> typedRules,
            Map<DayOfWeek, List<AvailabilityRule>> globalRules) {

        /**
         * The day's bookable windows. A {@link DateOverride} for the date REPLACES weekly hours: its
         * windows are used as-is (empty list => day off => no windows). When no override exists, the
         * weekly rules apply. Override precedence: per-type wins, else this owner's global; rules:
         * per-type for the day-of-week wins, else this owner's global for that day-of-week.
         */
        List<Window> windowsFor(LocalDate date) {
            DateOverride override = typedOverrides.get(date);
            if (override == null) {
                override = globalOverrides.get(date);
            }
            if (override != null) {
                return override.windows.stream()
                        .map(w -> new Window(w.startTime, w.endTime))
                        .toList();
            }
            DayOfWeek dow = date.getDayOfWeek();
            List<AvailabilityRule> rules = typedRules.get(dow);
            if (rules == null || rules.isEmpty()) {
                rules = globalRules.getOrDefault(dow, List.of());
            }
            return rules.stream()
                    .map(r -> new Window(r.startTime, r.endTime))
                    .toList();
        }
    }

    /**
     * Three queries (constant in horizon length): all weekly rules for this owner+type, all date
     * overrides in range, and all windows of the overrides actually selected. Split into per-type
     * and this-owner's-global maps so {@link Availability#windowsFor} can resolve each day in memory.
     */
    private Availability loadAvailability(MeetingType type, LocalDate from, LocalDate to) {
        // 1) Rules: this owner's per-type + global, grouped by day-of-week.
        List<AvailabilityRule> rules = AvailabilityRule.list(
                "ownerId = ?1 and (meetingTypeId = ?2 or meetingTypeId is null)", type.ownerId, type.id);
        Map<DayOfWeek, List<AvailabilityRule>> typedRules = rules.stream()
                .filter(r -> type.id.equals(r.meetingTypeId))
                .collect(Collectors.groupingBy(r -> r.dayOfWeek));
        Map<DayOfWeek, List<AvailabilityRule>> globalRules = rules.stream()
                .filter(r -> r.meetingTypeId == null)
                .collect(Collectors.groupingBy(r -> r.dayOfWeek));

        // 2) Overrides in range: per-type and this owner's global, keyed by date (first row wins on
        //    a duplicate date+scope, which the unique index should already prevent).
        List<DateOverride> overrides = DateOverride.list(
                "ownerId = ?1 and (meetingTypeId = ?2 or meetingTypeId is null) "
                + "and overrideDate >= ?3 and overrideDate <= ?4",
                type.ownerId, type.id, from, to);
        Map<LocalDate, DateOverride> typedOverrides = new HashMap<>();
        Map<LocalDate, DateOverride> globalOverrides = new HashMap<>();
        for (DateOverride o : overrides) {
            Map<LocalDate, DateOverride> target =
                    type.id.equals(o.meetingTypeId) ? typedOverrides : globalOverrides;
            target.putIfAbsent(o.overrideDate, o);
        }

        // 3) Windows: for every override actually selected (per-type beats global on a given date,
        //    but both scopes can apply on different dates, so load windows for all selected ones).
        //    One query, grouped by override id, preserving start-time ordering. Empty = day off.
        List<DateOverride> selected = new ArrayList<>(typedOverrides.values());
        selected.addAll(globalOverrides.values());
        if (!selected.isEmpty()) {
            List<Long> ids = selected.stream().map(o -> o.id).toList();
            Map<Long, List<DateOverrideWindow>> windowsByOverride = DateOverrideWindow
                    .<DateOverrideWindow>list("dateOverrideId in ?1 order by startTime asc", ids).stream()
                    .collect(Collectors.groupingBy(w -> w.dateOverrideId));
            for (DateOverride o : selected) {
                o.windows = windowsByOverride.getOrDefault(o.id, List.of());
            }
        }
        return new Availability(typedOverrides, globalOverrides, typedRules, globalRules);
    }
}
