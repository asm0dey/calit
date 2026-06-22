package site.asm0dey.calit.booking.events;

/** Feature 14: an approval-required booking was created as PENDING (awaiting owner decision). */
public record BookingRequested(Long bookingId) {}
