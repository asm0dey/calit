package site.asm0dey.calit.booking.events;

/**
 * The invitee removed a guest during a reschedule. The guest gets a cancel .ics; nobody else is notified.
 */
public record GuestRemoved(Long bookingId, Long guestId) {}
