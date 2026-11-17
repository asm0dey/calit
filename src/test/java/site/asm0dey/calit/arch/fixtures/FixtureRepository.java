package site.asm0dey.calit.arch.fixtures;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import site.asm0dey.calit.booking.Booking;

public class FixtureRepository implements PanacheRepositoryBase<Booking, Long> {
    Object query() {
        getEntityManager();
        return find("x");
    }

    public class Nested {
        Object query() {
            return find("x");
        }
    }
}
