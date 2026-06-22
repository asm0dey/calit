package site.asm0dey.calit.booking.events;

/** Feature 14: the owner approved a PENDING request (PENDING -> CONFIRMED). */
public record BookingApproved(Long bookingId) {}
