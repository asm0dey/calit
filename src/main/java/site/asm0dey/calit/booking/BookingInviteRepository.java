package site.asm0dey.calit.booking;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BookingInviteRepository implements PanacheRepositoryBase<BookingInvite, Long> {
    public long consume(String tokenHash, Long meetingTypeId, Instant now) {
        return BookingInvite.consume(tokenHash, meetingTypeId, now);
    }
}
