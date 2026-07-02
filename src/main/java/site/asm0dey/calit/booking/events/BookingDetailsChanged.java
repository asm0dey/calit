package site.asm0dey.calit.booking.events;

/** {@code byOwner} = the host edited name/description/guests from /me (vs. the invitee via manage link). */
public record BookingDetailsChanged(Long bookingId, boolean byOwner) {}
