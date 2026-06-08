package com.calit.availability;

import com.calit.domain.AvailabilityRule;
import com.calit.domain.DateOverride;
import com.calit.domain.MeetingType;
import com.calit.domain.OwnerSettings;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SlotService {

    /**
     * Raw bookable windows derived from work hours only.
     * Conflict/busy/buffer subtraction is applied by Plan 3 on top of this output.
     */
    public List<TimeSlot> generateRawSlots(MeetingType type, LocalDate from, LocalDate to) {
        OwnerSettings settings = OwnerSettings.get();
        if (settings == null) {
            throw new IllegalStateException(
                    "Owner settings not configured; set them via PUT /api/settings before generating slots.");
        }
        ZoneId zone = ZoneId.of(settings.timezone);
        List<TimeSlot> slots = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (Window window : windowsFor(type.id, date)) {
                LocalTime start = window.start();
                int step = type.effectiveSlotIntervalMinutes();
                while (!start.plusMinutes(type.durationMinutes).isAfter(window.end())) {
                    LocalTime end = start.plusMinutes(type.durationMinutes);
                    slots.add(new TimeSlot(
                            date.atTime(start).atZone(zone),
                            date.atTime(end).atZone(zone)));
                    start = start.plusMinutes(step);
                }
            }
        }
        return slots;
    }

    /** A bookable [start, end) time-of-day window for one day, from either an override or a weekly rule. */
    record Window(LocalTime start, LocalTime end) {}

    /**
     * Resolves the day's bookable windows. A {@link DateOverride} for the date REPLACES the weekly
     * hours: its windows are used as-is (empty list => day off => no windows). When no override
     * exists, the weekly rules apply. (Min-notice/horizon filters are applied in Plan 3, not here.)
     */
    List<Window> windowsFor(Long meetingTypeId, LocalDate date) {
        DateOverride override = DateOverride.resolve(meetingTypeId, date);
        if (override != null) {
            return override.windows.stream()
                    .map(w -> new Window(w.startTime, w.endTime))
                    .toList();
        }
        return rulesFor(meetingTypeId, date.getDayOfWeek()).stream()
                .map(r -> new Window(r.startTime, r.endTime))
                .toList();
    }

    /** Per-meeting-type rules win for a given day; otherwise fall back to global rules. */
    List<AvailabilityRule> rulesFor(Long meetingTypeId, DayOfWeek dow) {
        List<AvailabilityRule> override = AvailabilityRule.forMeetingType(meetingTypeId, dow);
        return override.isEmpty() ? AvailabilityRule.globalFor(dow) : override;
    }
}
