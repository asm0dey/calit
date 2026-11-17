package site.asm0dey.calit.arch.fixtures;

import module java.base;
import site.asm0dey.calit.booking.Booking;

public class EntityFinderTransitive {
    List<Booking> load(UUID groupId) {
        return Booking.group(groupId);
    }
}
