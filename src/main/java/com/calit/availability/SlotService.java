package com.calit.availability;

import com.calit.domain.AvailabilityRule;
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
            for (AvailabilityRule rule : rulesFor(type.id, date.getDayOfWeek())) {
                LocalTime start = rule.startTime;
                while (!start.plusMinutes(type.durationMinutes).isAfter(rule.endTime)) {
                    LocalTime end = start.plusMinutes(type.durationMinutes);
                    slots.add(new TimeSlot(
                            date.atTime(start).atZone(zone),
                            date.atTime(end).atZone(zone)));
                    start = end;
                }
            }
        }
        return slots;
    }

    /** Per-meeting-type rules win for a given day; otherwise fall back to global rules. */
    List<AvailabilityRule> rulesFor(Long meetingTypeId, DayOfWeek dow) {
        List<AvailabilityRule> override = AvailabilityRule.forMeetingType(meetingTypeId, dow);
        return override.isEmpty() ? AvailabilityRule.globalFor(dow) : override;
    }
}
