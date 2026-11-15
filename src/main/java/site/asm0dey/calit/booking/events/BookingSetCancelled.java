package site.asm0dey.calit.booking.events;

import module java.base;

/**
 * "Cancel all" on a Booking set: {@code cancelledIds} are the Invitee-facing rows this cancel flipped (in time
 * order), {@code leadBookingId} the set's lead. No per-row {@link BookingCancelled} fires for them.
 */
public record BookingSetCancelled(Long leadBookingId, List<Long> cancelledIds, boolean byOwner) {}
