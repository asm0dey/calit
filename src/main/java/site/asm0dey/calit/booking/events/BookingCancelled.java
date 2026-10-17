package site.asm0dey.calit.booking.events;

import site.asm0dey.calit.calendar.CalendarRef;

/**
 * {@code byOwner} = the host cancelled from /me or an owner email link (vs. the guest self-serving).
 * {@code mirrorRef} = where the Mirror event was before the cancel cleared the booking's address; null when it
 * had none (the mail then decides from the owner's write target, as for any address-less booking).
 */
public record BookingCancelled(Long bookingId, boolean byOwner, CalendarRef mirrorRef) {
    public BookingCancelled(Long bookingId, boolean byOwner) {
        this(bookingId, byOwner, null);
    }
}
