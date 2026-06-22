package site.asm0dey.calit.booking.events;

/** Feature 14: the owner declined a PENDING request (PENDING -> DECLINED). */
public record BookingDeclined(Long bookingId) {}
