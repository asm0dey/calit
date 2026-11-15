package site.asm0dey.calit.booking.events;

import module java.base;

/**
 * A booking was confirmed: an auto (no-approval) booking at once, or a co-hosted one by its last host's approval.
 * {@code setBookingIds}: for a Booking set, exactly the Invitee-facing rows this event covers (in time order, the
 * first being {@code bookingId}); empty for a lone Booking, which a set-level mail then treats as one Booking.
 */
public record BookingConfirmed(Long bookingId, List<Long> setBookingIds) {
    public BookingConfirmed(Long bookingId) {
        this(bookingId, List.of());
    }
}
