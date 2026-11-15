package site.asm0dey.calit.booking;

import java.time.ZoneId;
import java.util.List;
import site.asm0dey.calit.domain.MeetingType;

/**
 * Pure rules for a multi-slot booking set.
 */
public final class SetRules {
    public enum Blocked {
        NONE,
        SAME_DAY,
        OVERLAP,
        MAX
    }

    private SetRules() {
    }

    public static Blocked blocked(MeetingType type, List<SlotPick> picked, SlotPick candidate, ZoneId ownerZone) {
        if (picked.contains(candidate)) {
            return Blocked.NONE;
        }
        int max = type.multiSlotMax == null ? 1 : type.multiSlotMax;
        if (picked.size() >= max) {
            return Blocked.MAX;
        }
        for (var p : picked) {
            if (candidate.startUtc().isBefore(p.endUtc()) && p.startUtc().isBefore(candidate.endUtc())) {
                return Blocked.OVERLAP;
            }
        }
        if (!type.multiSlotSameDay) {
            var day = candidate.startUtc().atZone(ownerZone).toLocalDate();
            for (var p : picked) {
                if (p.startUtc().atZone(ownerZone).toLocalDate().equals(day)) {
                    return Blocked.SAME_DAY;
                }
            }
        }
        return Blocked.NONE;
    }

    /**
     * {@code allowed} is {@code MeetingTypeDuration.allowedDurations(type)} (DB-backed, so passed in).
     */
    public static void validate(MeetingType type, List<SlotPick> picks, List<Integer> allowed, ZoneId ownerZone) {
        int min = type.multiSlotMin == null ? 1 : type.multiSlotMin;
        int max = type.multiSlotMax == null ? 1 : type.multiSlotMax;
        if (picks.size() < min) {
            throw new SetRuleException("Pick between " + min + " and " + max + " sessions.", "pub_set_status_need", min);
        }
        if (picks.size() > max) {
            throw new SetRuleException("Pick between " + min + " and " + max + " sessions.", "pub_set_reason_max", max);
        }
        for (var p : picks) {
            if (!allowed.contains(p.minutes())) {
                throw new BookingValidationException("Session length not allowed: " + p.minutes() + " minutes.");
            }
            var others = picks.stream().filter(o -> o != p).toList();
            var b = blocked(type, others, p, ownerZone);
            if (b == Blocked.OVERLAP) {
                throw new SetRuleException("Sessions conflict (" + b + "): " + p.value(), "pub_set_reason_overlap", 0);
            }
            if (b == Blocked.SAME_DAY) {
                throw new SetRuleException("Sessions conflict (" + b + "): " + p.value(), "pub_set_reason_same_day", 0);
            }
        }
    }
}
