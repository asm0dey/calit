package site.asm0dey.calit.scheduler;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Feature 15: a scheduled reminder for one CONFIRMED booking, due at
 * (booking.startUtc - lead time). {@link #sentAt} null means unsent; the
 * scheduler tick (ReminderScheduler) claims due unsent rows with
 * SELECT ... FOR UPDATE SKIP LOCKED and stamps {@code sentAt}.
 */
@Entity
@Table(name = "reminder")
public class Reminder extends PanacheEntityBase {

    /** The only reminder kind in v1. */
    public static final String KIND_REMINDER = "REMINDER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "booking_id", nullable = false)
    public Long bookingId;

    @Column(name = "send_at", nullable = false)
    public Instant sendAt;

    @Column(name = "kind", nullable = false, length = 24)
    public String kind;

    /** Null = unsent. Stamped (in the claiming transaction) when the reminder is dispatched. */
    @Column(name = "sent_at")
    public Instant sentAt;

    /** Deletes every still-unsent reminder for a booking (cancel/decline/reschedule). Idempotent. */
    public static void deleteUnsentFor(Long bookingId) {
        delete("bookingId = ?1 and sentAt is null", bookingId);
    }
}
