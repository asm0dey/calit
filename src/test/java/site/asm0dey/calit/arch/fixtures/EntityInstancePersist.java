package site.asm0dey.calit.arch.fixtures;

import site.asm0dey.calit.booking.Booking;

public class EntityInstancePersist {
    void save() {
        new Booking().persist();
    }
}
