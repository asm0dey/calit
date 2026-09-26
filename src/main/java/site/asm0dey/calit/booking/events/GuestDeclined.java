package site.asm0dey.calit.booking.events;

/**
 * A guest declined their invitation. The invitee is notified; the guest gets a cancel .ics.
 */
public record GuestDeclined(Long bookingId, Long guestId) {}
