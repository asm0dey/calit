package site.asm0dey.calit.booking.events;

/** {@code byOwner} = the host cancelled from /me or an owner email link (vs. the guest self-serving). */
public record BookingCancelled(Long bookingId, boolean byOwner) {}
