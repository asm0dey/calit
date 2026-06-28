package site.asm0dey.calit.booking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

/**
 * One guest the invitee added to a booking. Owner-scoped (owner_id copied from the booking) per the
 * multi-tenancy invariant. Guests are notified only through calit-sent .ics emails; declineToken is
 * the unguessable key for the guest's "I can't attend" link ({base-url}/guest/{declineToken}/decline).
 */
@Entity
@Table(name = "booking_guest")
public class BookingGuest extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    @Column(name = "booking_id", nullable = false)
    public Long bookingId;

    @Column(nullable = false, length = 254)
    public String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public GuestStatus status = GuestStatus.INVITED;

    @Column(name = "decline_token", nullable = false, length = 36, unique = true)
    public String declineToken;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Active (INVITED) guests for a booking — the current email recipients, ordered by id. */
    public static List<BookingGuest> activeForBooking(Long bookingId) {
        return list("bookingId = ?1 and status = ?2 order by id", bookingId, GuestStatus.INVITED);
    }

    /** Every guest row for a booking regardless of status (used by reschedule reconciliation). */
    public static List<BookingGuest> allForBooking(Long bookingId) {
        return list("bookingId = ?1 order by id", bookingId);
    }

    /** The guest row for this booking+email (any status), or null. Email match is case-insensitive. */
    public static BookingGuest findInBooking(Long bookingId, String email) {
        return find("bookingId = ?1 and lower(email) = lower(?2)", bookingId, email).firstResult();
    }

    /** Loads a guest by its unguessable decline token, or null. */
    public static BookingGuest findByDeclineToken(String declineToken) {
        return find("declineToken", declineToken).firstResult();
    }
}
