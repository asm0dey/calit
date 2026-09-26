package site.asm0dey.calit.scheduler;

import module java.base;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
 * <p>These bookings are all in the past, so there is nothing to cancel: each batch claims up to
 * {@link #BATCH} ids and passes the whole list to {@link PrivacyService#anonymise(java.util.Collection)}
 * in the SAME transaction as the claim — one set-based erasure, not a per-id loop. That mirrors the
 * erase button's own erasure path, minus the cancel step a past booking no longer needs. A tick
 * keeps claiming batches, each in its own short transaction, until one comes back short or
 * {@link #TIME_BUDGET} runs out, so a backlog (the day an operator first opts in) drains in one
 * night rather than at 200 rows per replica per day.
 */
@ApplicationScoped
public class RetentionScheduler {
    /**
     * One transaction's ceiling: keeps each claim + erasure short.
     */
    static final int BATCH = 200;
    /**
     * How long one tick may keep claiming batches before leaving the rest for the next day.
     */
    static final Duration TIME_BUDGET = Duration.ofMinutes(5);
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
     * owners disagree. {@code LEFT JOIN owner_settings}: an owner row is seeded on every real
     * account-creation path, but the join must not silently exclude a booking whose owner somehow
     * lacks one — {@code COALESCE} then falls straight through to the instance default for it, same
     * as an owner row with a null {@code booking_retention_days}. {@code FOR UPDATE OF b} names only
     * {@code booking}: a bare {@code FOR UPDATE} would try to lock the outer-joined
     * {@code owner_settings} row too, and Postgres rejects {@code SKIP LOCKED} on the nullable side
     * of an outer join.
     *
     * <p>{@code COALESCE(...) IS NOT NULL} is checked BEFORE the interval arithmetic runs, as its own
     * AND-ed condition, rather than trusting the interval expression to exclude a null window on its
     * own: Postgres's {@code LEAST}/{@code GREATEST} ignore NULL arguments rather than propagating
     * them, so {@code LEAST(NULL, cap)} evaluates to {@code cap} — NOT null. Without the explicit
     * guard, a genuinely unset window ("keep forever") would be silently miscomputed as a real
     * capped window instead of excluding the row. The guard is redundant with correct short-circuit
     * evaluation of the surrounding AND, but SQL doesn't promise that ordering, so it stays explicit.
     *
     * <p>{@code LEAST(COALESCE(...), :cap)}: see {@link PrivacyConfig#MAX_RETENTION_DAYS} — a huge
     * window, however it got into the column, must not reach {@code make_interval} unclamped or
     * Postgres raises "timestamp out of range" and (since the claim + erasure are ONE transaction)
     * that one poison row stops retention for every owner that tick.
     *
     * <p>Each batch's claim and erasure run in ONE transaction: {@code QuarkusTransaction.requiringNew()}
     * opens it, and {@link PrivacyService#anonymise(java.util.Collection)} — {@code @Transactional}
     * with REQUIRED propagation — joins that same transaction rather than opening its own. Both the
     * row lock and the erasure commit together, or neither does.
     *
     * <p>A tick failure is logged with its context (batch ceiling, instance default) before being
     * rethrown — mirroring how {@link ReminderScheduler}/{@code PendingExpiryScheduler} never
     * swallow a whole-tick failure, only a single poison item's own risky step. Rethrowing lets the
     * failing batch roll back cleanly (earlier batches stay committed) and reach Quarkus's own
     * scheduler failure logging.
     */
    void sweep() {
        sweep(BATCH);
    }

    /**
     * {@code batch} is a parameter only so a test can drain several batches without seeding
     * hundreds of rows; production always passes {@link #BATCH}.
     *
     * @return how many bookings this tick anonymised
     */
    int sweep(int batch) {
        Integer instanceDefault = config.bookingRetentionDays().orElse(null);
        var deadline = Instant.now().plus(TIME_BUDGET);
        var total = 0;
        try {
            int claimed;
            do {
                int[] counts = QuarkusTransaction
                    .requiringNew()
                    .call(() -> sweepBatch(batch, instanceDefault));
                claimed = counts[0];
                total += counts[1];
            } while (claimed == batch && Instant.now().isBefore(deadline));
        } catch (RuntimeException e) {
            Log.errorf(
                    e,
                    "PRIVACY retention sweep tick failed after %d booking(s) (batch=%d instanceDefault=%s)",
                    total,
                    batch,
                    instanceDefault
            );
            throw e;
        }
        if (total > 0) {
            Log.infof("PRIVACY retention sweep anonymised %d booking(s)", total);
        }
        return total;
    }

    /**
     * One claim + erasure, in the caller's transaction. Returns {claimed, erased}.
     */
    private int[] sweepBatch(int batch, Integer instanceDefault) {
        @SuppressWarnings("unchecked")
        List<Number> ids = em
            .createNativeQuery(
                    "SELECT b.id FROM booking b "
                    + "LEFT JOIN owner_settings os ON os.owner_id = b.owner_id "
                    + "WHERE b.erased_at IS NULL "
                    + "  AND COALESCE(os.booking_retention_days, :instanceDefault) IS NOT NULL "
                    + "  AND b.end_utc < now() - make_interval("
                    + "        days => LEAST(COALESCE(os.booking_retention_days, :instanceDefault), :cap)) "
                    + "ORDER BY b.end_utc "
                    + "FOR UPDATE OF b SKIP LOCKED "
                    + "LIMIT :batch"
            )
            .setParameter("instanceDefault", instanceDefault)
            .setParameter("cap", PrivacyConfig.MAX_RETENTION_DAYS)
            .setParameter("batch", batch)
            .getResultList();

        List<Long> claimed = new ArrayList<>();
        ids.forEach(n -> claimed.add(n.longValue()));
        return new int[] {claimed.size(), privacy.anonymise(claimed)};
    }
}
