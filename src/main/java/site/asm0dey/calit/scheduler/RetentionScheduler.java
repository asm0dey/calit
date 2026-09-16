package site.asm0dey.calit.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import site.asm0dey.calit.privacy.PrivacyConfig;
import site.asm0dey.calit.privacy.PrivacyService;

/**
 * Anonymises bookings once their retention window has elapsed. Runs on EVERY replica, daily, with
 * no leader: each tick claims rows with SELECT ... FOR UPDATE SKIP LOCKED, the same pattern
 * {@link ReminderScheduler} uses.
 *
 * <p>The window is per owner ({@code owner_settings.booking_retention_days}) falling back to the
 * instance default ({@code calit.retention.booking-days}). Both unset means keep forever, which is
 * the shipped default — an upgrade changes nothing until an operator opts in.
 *
 * <p>These bookings are all in the past, so there is nothing to cancel: one tick claims up to
 * {@link #BATCH} ids and passes the whole list to {@link PrivacyService#anonymise(java.util.Collection)}
 * in the SAME transaction as the claim — one set-based erasure, not a per-id loop. That mirrors the
 * erase button's own erasure path, minus the cancel step a past booking no longer needs.
 */
@ApplicationScoped
public class RetentionScheduler {

    /** One tick's ceiling. A backlog drains over successive days rather than in one long transaction. */
    private static final int BATCH = 200;

    final EntityManager em;

    final PrivacyService privacy;

    final PrivacyConfig config;

    @Inject
    public RetentionScheduler(EntityManager em, PrivacyService privacy, PrivacyConfig config) {
        this.em = em;
        this.privacy = privacy;
        this.config = config;
    }

    @Scheduled(cron = "0 17 3 * * ?")
    void dailySweep() {
        sweep();
    }

    /**
     * One pass. The window arithmetic runs in SQL because it is per-row: each booking is measured
     * against ITS OWNER's window, so a single Java-side cutoff instant would be wrong the moment two
     * owners disagree. COALESCE picks the owner's value, then the instance default; a NULL result
     * means "keep forever" and the row is not selected at all.
     *
     * <p>The claim and the erasure run in ONE transaction: {@code QuarkusTransaction.requiringNew()}
     * opens it, and {@link PrivacyService#anonymise(java.util.Collection)} — {@code @Transactional}
     * with REQUIRED propagation — joins that same transaction rather than opening its own. Both the
     * row lock and the erasure commit together, or neither does.
     */
    void sweep() {
        Integer instanceDefault = config.bookingRetentionDays().orElse(null);
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery("SELECT b.id FROM booking b "
                            + "JOIN owner_settings os ON os.owner_id = b.owner_id "
                            + "WHERE b.erased_at IS NULL "
                            + "  AND COALESCE(os.booking_retention_days, :instanceDefault) IS NOT NULL "
                            + "  AND b.end_utc < now() - make_interval("
                            + "        days => COALESCE(os.booking_retention_days, :instanceDefault)) "
                            + "ORDER BY b.end_utc "
                            + "FOR UPDATE OF b SKIP LOCKED "
                            + "LIMIT :batch")
                    .setParameter("instanceDefault", instanceDefault)
                    .setParameter("batch", BATCH)
                    .getResultList();

            List<Long> claimed = new ArrayList<>();
            ids.forEach(n -> claimed.add(n.longValue()));

            int erased = privacy.anonymise(claimed);
            if (erased > 0) {
                Log.infof("PRIVACY retention sweep anonymised %d booking(s)", erased);
            }
        });
    }
}
