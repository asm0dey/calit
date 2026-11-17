package site.asm0dey.calit.arch.fixtures;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.persistence.LockModeType;
import site.asm0dey.calit.booking.Booking;

public class RepositoryByIdWithLock {
    void use(PanacheRepositoryBase<Booking, Long> repo) {
        repo.findById(1L, LockModeType.PESSIMISTIC_WRITE);
    }
}
