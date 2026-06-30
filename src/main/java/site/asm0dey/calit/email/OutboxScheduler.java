package site.asm0dey.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;

/**
 * Retries parked mail. Runs on EVERY replica every 60s, multi-node-safe with NO leader: each tick
 * claims due unsent rows with SELECT ... FOR UPDATE SKIP LOCKED, so a concurrent replica's identical
 * query skips the rows this one locked -- only one replica processes a given row per tick.
 *
 * Delivery is at-least-once (the only achievable guarantee against a non-transactional SMTP server):
 * if a send succeeds but the tx fails to commit -- e.g. the pod is killed between send and commit --
 * the row stays unsent and is re-sent next tick. A duplicate on crash is preferable to a lost mail,
 * which is the whole point of the outbox.
 *
 * ponytail: the row lock is held across the SMTP send (one tx per tick, LIMIT 20). Fine at self-hosted
 * scale; if send latency x volume causes lock contention, switch to a lease-token claim (set
 * next_attempt_at forward in a short claim tx, then send unlocked, then mark sent in a second tx).
 */
@ApplicationScoped
public class OutboxScheduler {

    private static final int BATCH = 20;

    @Inject
    EntityManager em;

    @Inject
    MailSender mailSender;

    @Scheduled(every = "60s")
    void scheduledTick() {
        dispatchDueMail();
    }

    /** Package-private so tests can drive one tick deterministically. */
    void dispatchDueMail() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery("SELECT id FROM email_outbox "
                            + "WHERE sent_at IS NULL AND next_attempt_at <= now() "
                            + "ORDER BY next_attempt_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT " + BATCH)
                    .getResultList();

            for (Number n : ids) {
                EmailOutbox r = EmailOutbox.findById(n.longValue());
                if (r == null) {
                    // can't happen (row is locked in this tx) -- guard so a stray null never
                    // aborts the batch and discards already-sent rows' progress
                    continue;
                }
                if (r.pastDeadline(Instant.now())) {
                    r.markExpired(); // e.g. reset-token already expired -- don't deliver a dead link
                } else {
                    try {
                        mailSender.sendNow(null, r.recipient, r.subject, r.htmlBody, r.icsBytes);
                        r.sentAt = Instant.now(); // marked within the lock-holding tx
                    } catch (Exception e) {
                        r.deadOrBackoff(e.getMessage()); // bump attempts / reschedule / mark dead
                    }
                }
            }
        });
    }
}
