package site.asm0dey.calit.booking;

public enum BookingStatus {
    /** Feature 14: an approval-required request holding the slot, awaiting owner approve/decline. */
    PENDING,
    CONFIRMED,
    CANCELLED,
    /** Feature 14: owner declined a pending request; frees the slot. */
    DECLINED
}
