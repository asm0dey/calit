package site.asm0dey.calit.booking;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BookingGuestRepository implements PanacheRepositoryBase<BookingGuest, Long> {
    public List<BookingGuest> activeForBooking(Long bookingId) {
        return BookingGuest.activeForBooking(bookingId);
    }

    /**
     * Every guest row for a booking regardless of status (used by reschedule reconciliation).
     */
    public List<BookingGuest> allForBooking(Long bookingId) {
        return list("bookingId = ?1 order by id", bookingId);
    }

    /**
     * The guest row for this booking+email (any status), or null. Email match is case-insensitive.
     */
    public BookingGuest findInBooking(Long bookingId, String email) {
        return find("bookingId = ?1 and lower(email) = lower(?2)", bookingId, email).firstResult();
    }

    public BookingGuest findByDeclineToken(String declineToken) {
        return BookingGuest.findByDeclineToken(declineToken);
    }

    /**
     * Re-reads the row from the database, dropping what this context loaded earlier.
     */
    public void reread(BookingGuest guest) {
        getEntityManager().refresh(guest);
    }
}
