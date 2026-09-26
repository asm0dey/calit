package site.asm0dey.calit.booking.events;

import module java.base;

/**
 * {@code byOwner} = the host rescheduled from /me or an owner email link (vs. the guest self-serving).
 */
public record BookingRescheduled(Long bookingId, Instant oldStartUtc, boolean byOwner) {}
