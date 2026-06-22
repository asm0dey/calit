package site.asm0dey.calit.scheduler;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.google.GoogleTokenService;
import site.asm0dey.calit.i18n.AppLocales;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Feature: Google disconnect detection. Runs on EVERY replica every probe-interval. Multi-node-safe
 * with NO leader: each phase claims rows with SELECT ... FOR UPDATE SKIP LOCKED, so a concurrent
 * replica's identical query skips rows this one already locked.
 */
@ApplicationScoped
public class GoogleConnectionScheduler {

    @ConfigProperty(name = "calit.google.probe-interval")
    Duration probeInterval;

    @Inject
    EntityManager em;

    @Inject
    GoogleTokenService tokens;

    @Inject
    EmailService emailService;

    @Scheduled(every = "{calit.google.probe-interval}")
    void tick() {
        probeDueCredentials();
        notifyPendingDisconnects();
    }

    /**
     * Claims credentials not probed within half the interval (stamping last_probed_at so other
     * replicas skip them), then forces a refresh per credential in its own transaction. The
     * half-interval grace stops staggered replica timers from re-probing the same account each tick.
     * ponytail: last_probed_at gating is an optimization (avoids redundant Google calls); the
     * notify gate below is what guarantees correctness.
     */
    public void probeDueCredentials() {
        List<Long> ids = claimDueForProbe();
        Instant now = Instant.now();
        for (Long id : ids) {
            tokens.probe(id, now); // own @Transactional; sets/clears needs_reconnect
        }
    }

    List<Long> claimDueForProbe() {
        long graceSeconds = Math.max(1, probeInterval.toSeconds() / 2);
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM google_credential "
                            + "WHERE last_probed_at IS NULL "
                            + "   OR last_probed_at <= now() - make_interval(secs => :secs) "
                            + "ORDER BY last_probed_at NULLS FIRST "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("secs", (double) graceSeconds)
                    .getResultList();
            List<Long> claimed = new ArrayList<>();
            Instant now = Instant.now();
            for (Number n : ids) {
                Long id = n.longValue();
                GoogleCredential c = GoogleCredential.findById(id);
                c.lastProbedAt = now; // stamped in the lock-holding transaction
                claimed.add(id);
            }
            return claimed;
        });
    }

    /**
     * Claims disconnected-and-not-yet-notified credentials, stamps reconnect_notified_at in the
     * lock-holding transaction (exactly-once email gate), then emails each owner. MailSender.send
     * never throws (outbox fallback), so a stamped mail is always eventually delivered.
     */
    public void notifyPendingDisconnects() {
        List<Pending> pending = claimUnnotifiedDisconnects();
        for (Pending p : pending) {
            emailService.sendGoogleDisconnected(p.ownerEmail(), p.accountEmail(), p.locale());
        }
    }

    private record Pending(String ownerEmail, String accountEmail, Locale locale) {}

    List<Pending> claimUnnotifiedDisconnects() {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM google_credential "
                            + "WHERE needs_reconnect = true AND reconnect_notified_at IS NULL "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .getResultList();
            List<Pending> out = new ArrayList<>();
            Instant now = Instant.now();
            for (Number n : ids) {
                GoogleCredential c = GoogleCredential.findById(n.longValue());
                c.reconnectNotifiedAt = now; // claim: prevents any replica re-sending
                OwnerSettings s = OwnerSettings.forOwner(c.ownerId);
                if (s != null) {
                    out.add(new Pending(s.ownerEmail, c.accountEmail, AppLocales.pick(s.locale)));
                }
            }
            return out;
        });
    }
}
