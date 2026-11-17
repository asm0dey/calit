package site.asm0dey.calit.arch.fixtures;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import site.asm0dey.calit.booking.Booking;

public class RepositoryQueryString {
    Object query(PanacheRepositoryBase<Booking, Long> bookingRepo) {
        return bookingRepo.find("x");
    }
}
