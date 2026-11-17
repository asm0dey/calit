package site.asm0dey.calit.arch.fixtures;

import site.asm0dey.calit.booking.Booking;

public class EntityConstruction {
    Object build() {
        new FixtureEntity();
        return new Booking();
    }
}
