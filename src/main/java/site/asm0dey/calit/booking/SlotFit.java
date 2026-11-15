package site.asm0dey.calit.booking;

import module java.base;

/**
 * Pure slot-fit and reschedule rules: whether a slot is bookable against a host's busy set, and how a reschedule
 * treats approval.
 */
public final class SlotFit {
    private SlotFit() {
    }

    /**
     * Whether a slot starts inside {@code [earliest, latest]} (both ends inclusive) and its buffered interval
     * {@code [slotStart - bufBefore, slotEnd + bufAfter)} overlaps no busy interval (half-open: back-to-back fits).
     */
    static boolean fits(
            Instant slotStart,
            Instant slotEnd,
            Instant earliest,
            Instant latest,
            int bufBeforeMin,
            int bufAfterMin,
            List<Interval> busy
    ) {
        // Feature 11: drop too-soon (before now+minNotice) and too-far (after now+horizon) slots.
        if (slotStart.isBefore(earliest) || slotStart.isAfter(latest)) {
            return false;
        }
        var buffered = new Interval(slotStart.minusSeconds(60L * bufBeforeMin), slotEnd.plusSeconds(60L * bufAfterMin));
        return !buffered.overlapsAny(busy);
    }

    /**
     * Whether a reschedule returns the booking to the approval queue. A single row re-approves only when the Invitee
     * moves it (a host already knows their own slot is free); a group always re-approves, whoever moves it.
     */
    static boolean needsReApproval(boolean requiresApproval, boolean byOwner, boolean group) {
        return requiresApproval && (group || !byOwner);
    }

    /**
     * A group row's status after a move: unchanged without re-approval; otherwise the initiating host's row stays
     * CONFIRMED and every other row goes PENDING.
     */
    static BookingStatus groupRowStatusAfterMove(
            boolean reApproval,
            Long rowOwnerId,
            Long initiatorOwnerId,
            BookingStatus current
    ) {
        if (!reApproval) {
            return current;
        }
        return initiatorOwnerId != null && initiatorOwnerId.equals(rowOwnerId)
                ? BookingStatus.CONFIRMED
                : BookingStatus.PENDING;
    }
}
