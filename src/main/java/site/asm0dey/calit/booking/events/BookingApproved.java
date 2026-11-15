package site.asm0dey.calit.booking.events;

import module java.base;

/**
 * Feature 14: the owner approved a PENDING request (PENDING -> CONFIRMED).
 * {@code setBookingIds}: for a Booking set, exactly the Invitee-facing rows this event covers (in time order, the
 * first being {@code bookingId}); empty for a lone Booking, which a set-level mail then treats as one Booking.
 */
public record BookingApproved(Long bookingId, List<Long> setBookingIds) {
    public BookingApproved(Long bookingId) {
        this(bookingId, List.of());
    }
}
