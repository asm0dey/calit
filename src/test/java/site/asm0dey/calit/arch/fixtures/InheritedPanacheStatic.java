package site.asm0dey.calit.arch.fixtures;

import site.asm0dey.calit.booking.Booking;

public class InheritedPanacheStatic {
    Object load() {
        return Booking.findById(1L);
    }
}
