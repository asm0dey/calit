package site.asm0dey.calit.arch.fixtures;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import site.asm0dey.calit.booking.Booking;

public class RepositoryById {
    void use(PanacheRepositoryBase<Booking, Long> repo, Booking b) {
        repo.findById(1L);
        repo.persist(b);
        repo.delete(b);
        repo.flush();
    }
}
