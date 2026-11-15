package site.asm0dey.calit.booking.events;

import module java.base;

/**
 * Feature 14: the owner declined a PENDING request (PENDING -> DECLINED).
 * {@code setBookingIds}: for a Booking set, exactly the Invitee-facing rows this event covers (in time order, the
 * first being {@code bookingId}); empty for a lone Booking, which a set-level mail then treats as one Booking.
 */
public record BookingDeclined(Long bookingId, List<Long> setBookingIds) {
    public BookingDeclined(Long bookingId) {
        this(bookingId, List.of());
    }
}
