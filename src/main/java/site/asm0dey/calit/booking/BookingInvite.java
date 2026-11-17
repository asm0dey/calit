package site.asm0dey.calit.booking;

import module java.base;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * Single-use, expiring invite to book one meeting type. Only the SHA-256 hash of the raw token is
 * stored; the row is deleted when a booking consumes it and is never restored.
 */
@Entity
@Table(name = "booking_invite")
public class BookingInvite extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
    @Column(name = "meeting_type_id", nullable = false)
    public Long meetingTypeId;
    @Column(name = "token_hash", nullable = false, unique = true)
    public String tokenHash;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
}
