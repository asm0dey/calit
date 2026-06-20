package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.booking.events.BookingDeclined;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature 14: auto-expire approval-mode (PENDING) bookings whose hold window has
 * elapsed. Runs on EVERY replica every 60s. Multi-node-safe with NO leader: each
 * tick claims expired PENDING rows with SELECT ... FOR UPDATE SKIP LOCKED, so two
 * replicas never decline the same booking. Flipping PENDING -> DECLINED drops the
 * row from the booking_no_overlap_held guard (partial on PENDING/CONFIRMED),
 * which immediately frees the held slot for a new booking.
 */
@ApplicationScoped
public class PendingExpiryScheduler {

    @ConfigProperty(name = "calit.approval.hold-hours", defaultValue = "24")
    int holdHours;

    @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30")
    int graceSeconds;

    @Inject
    EntityManager em;

    @Inject
    Event<BookingDeclined> bookingDeclinedEvent;

    @Scheduled(every = "60s")
    void expirePendingBookings() {
        List<Long> declinedIds = claimAndDeclineExpired();
        // Fire AFTER the flip transaction commits. Each fire runs in its own committed tx
        // so the AFTER_SUCCESS observers (Plan 4 declined email, ReminderScheduler.onDeclined)
        // are reliably delivered.
        for (Long id : declinedIds) {
            QuarkusTransaction.requiringNew().run(() -> bookingDeclinedEvent.fire(new BookingDeclined(id)));
        }
    }

    /**
     * Claims expired PENDING bookings FOR UPDATE SKIP LOCKED and flips them to DECLINED
     * in the SAME transaction. Expiry = min(createdAt + holdHours, startUtc) <= now().
     */
    List<Long> claimAndDeclineExpired() {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM booking "
                            + "WHERE status = 'PENDING' "
                            + "AND LEAST(created_at + (:holdHours * INTERVAL '1 hour'), start_utc) "
                            + "    <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY created_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("holdHours", holdHours)
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            List<Long> declinedIds = new ArrayList<>();
            for (Number n : ids) {
                Long id = n.longValue();
                Booking b = Booking.findById(id);
                b.status = BookingStatus.DECLINED;   // flipped within the lock-holding transaction
                declinedIds.add(id);
            }
            return declinedIds;
        });
    }
}
