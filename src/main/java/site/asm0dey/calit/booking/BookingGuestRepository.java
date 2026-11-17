package site.asm0dey.calit.booking;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BookingGuestRepository implements PanacheRepositoryBase<BookingGuest, Long> {
    public List<BookingGuest> activeForBooking(Long bookingId) {
        return BookingGuest.activeForBooking(bookingId);
    }

    public List<BookingGuest> allForBooking(Long bookingId) {
        return BookingGuest.allForBooking(bookingId);
    }

    public BookingGuest findInBooking(Long bookingId, String email) {
        return BookingGuest.findInBooking(bookingId, email);
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
