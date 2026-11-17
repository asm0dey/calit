package site.asm0dey.calit.arch.fixtures;

import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.domain.OwnerSettings;

public class PureEntityMethods {
    Object use(Booking b) {
        b.isErased();
        OwnerSettings.coerceZone("UTC");
        return MeetingTypeHost.of(1L, 1L, "HOST", "ACCEPTED");
    }
}
