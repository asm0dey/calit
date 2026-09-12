package site.asm0dey.calit.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.notify.NotificationDispatcher;

/**
 * Feature 14: auto-expire approval-mode (PENDING) bookings whose hold window has
 * elapsed. Runs on EVERY replica every 60s, leaderless, with NO row-claim locks: the
 * candidate SELECT below is a plain read (no {@code FOR UPDATE}, no
 * {@code SKIP LOCKED}) and takes no row locks at all. Exclusivity is provided
 * entirely by a per-{group, or single booking} transaction-scoped, NON-BLOCKING
 * Postgres advisory lock ({@code pg_try_advisory_xact_lock}): a tick only ever
 * touches rows it has exclusively "won" the advisory lock for, and a losing tick
 * skips the candidate outright rather than waiting -- so no wait-for cycle, and
 * therefore no deadlock, can ever form, even across two ticks whose candidate
 * batches straddle two different groups. Flipping PENDING -> DECLINED drops the
 * row from the booking_no_overlap_held guard (partial on PENDING/CONFIRMED),
 * which immediately frees the held slot for a new booking.
 *
 * <p>History: an earlier version claimed rows with {@code SELECT ... FOR UPDATE
 * SKIP LOCKED} and layered a per-group advisory lock on top to stop two ticks from
 * both declining the same group. That closed the single-group race but left a
 * residual two-group deadlock: a tick's claim query still took row-claim locks on
 * rows of MULTIPLE groups per batch (including groups it does NOT own the advisory
 * lock for and therefore skips declining) -- so tick A holding claim locks on
 * group1+group2's rows while owning group1's advisory lock, and tick B holding
 * claim locks on group2+group1's rows while owning group2's advisory lock, produced
 * a classic AB-BA cycle: A's decline of group1 blocks on B's claimed group1 row,
 * B's decline of group2 blocks on A's claimed group2 row. Dropping the row-claim
 * lock entirely (this version) removes the only lock a losing/skipping tick could
 * still be holding when another tick needs it, eliminating the cycle.
 */
@ApplicationScoped
public class PendingExpiryScheduler {

    final int holdHours;

    final int graceSeconds;

    final EntityManager em;

    final EmailService emailService;

    final NotificationDispatcher channels;

    @Inject
    public PendingExpiryScheduler(
            EntityManager em,
            EmailService emailService,
            NotificationDispatcher channels,
            @ConfigProperty(name = "calit.approval.hold-hours", defaultValue = "24") int holdHours,
            @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30") int graceSeconds) {
        this.em = em;
        this.emailService = emailService;
        this.channels = channels;
        this.holdHours = holdHours;
        this.graceSeconds = graceSeconds;
    }

    /**
     * Feature 14 expiry tick. Runs on EVERY replica every 60s, leaderless (exclusivity via a
     * per-group/per-booking Postgres advisory lock -- see the class javadoc; NOT {@code FOR UPDATE
     * SKIP LOCKED}, which was dropped after the AB-BA deadlock finding described there).
     * Per expired booking, in the SAME transaction as the claim: flip PENDING -> DECLINED, drop its
     * unsent reminder, and enqueue the declined email to the outbox (crash-safe). OutboxScheduler
     * delivers it. A node dying mid-tick loses nothing: all three commit together or not at all.
     */
    @Scheduled(every = "60s")
    void expirePendingBookings() {
        claimAndDeclineExpired();
    }

    /** Advisory-lock key-space prefix for a group; must never collide with {@link #SINGLE_LOCK_PREFIX}. */
    private static final String GROUP_LOCK_PREFIX = "group:";

    /** Advisory-lock key-space prefix for a single-host booking (no group); see {@link #GROUP_LOCK_PREFIX}. */
    private static final String SINGLE_LOCK_PREFIX = "booking:";

    /**
     * Reads candidate expired PENDING bookings with a plain (lock-free) SELECT and, per candidate,
     * tries a NON-BLOCKING Postgres advisory lock keyed by group (multi-host) or by booking id
     * (single-host) before touching it. A tick that loses the try-lock skips the candidate
     * outright -- never waits -- so no tick can ever hold a row lock that another tick needs,
     * which is what made the deadlock possible in the FOR-UPDATE-based version. Winning the lock
     * makes a tick the sole owner of that group/booking for the round; only then does it flip
     * PENDING -> DECLINED, delete any unsent reminder, and enqueue the declined email, all in the
     * SAME tx (crash-safe: a node dying mid-tick loses nothing, everything commits together or not
     * at all). Expiry = min(createdAt + holdHours, startUtc) <= now() + grace. A render failure for
     * one poison booking is caught/logged so it can't roll back the whole batch.
     *
     * <p>Outbound channel notifications are dispatched AFTER this transaction commits, never
     * inside it. An auto-expiry fires no {@code BookingDeclined} event (it does the reminder
     * cleanup and the mail enqueue itself), so without this explicit dispatch an auto-expired
     * booking would email the host but send no channel message, while an owner-initiated decline
     * does both. A broken channel must not roll back or delay the decline.
     */
    void claimAndDeclineExpired() {
        List<Long> declined = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Object[]> candidates = em.createNativeQuery("SELECT id, group_id FROM booking "
                            + "WHERE status = 'PENDING' "
                            + "AND LEAST(created_at + (:holdHours * INTERVAL '1 hour'), start_utc) "
                            + "    <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY created_at "
                            + "LIMIT 50")
                    .setParameter("holdHours", holdHours)
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            for (Object[] row : candidates) {
                Long id = ((Number) row[0]).longValue();
                // Defensive parse: driver/Hibernate version can return a uuid column as either
                // java.util.UUID or its String form from a scalar native query.
                var groupId = row[1] == null ? null : UUID.fromString(row[1].toString());
                processCandidate(id, groupId, declined);
            }
        });
        for (Long bookingId : declined) {
            channels.notifyDeclined(bookingId); // swallows its own failures
        }
    }

    /**
     * Per-candidate: try the non-blocking advisory lock, skip on loss, reload the booking and
     * re-check its status, then dispatch to {@link #declineGroup} or {@link #declineSingle}. Runs
     * synchronously inside the SAME {@code requiringNew()} transaction started by the caller's loop
     * in {@link #claimAndDeclineExpired()} -- this is a plain private helper, not a separate
     * transactional boundary, so the advisory lock and the eventual flip + email enqueue still
     * commit or roll back together. Split out to keep {@code claimAndDeclineExpired}'s cognitive
     * complexity in check.
     *
     * <p>{@code declined} collects the booking id whose host channels must be notified once the
     * caller's transaction has committed -- the lead row's id for a group, the booking's own id
     * for a single-host booking.
     */
    private void processCandidate(Long id, UUID groupId, List<Long> declined) {
        // Non-blocking: two ticks racing on the same group/booking never wait on each
        // other, so no wait-for cycle -- and thus no deadlock -- can ever form. The prefix
        // keeps the group and single-booking key spaces disjoint (a group_id and a booking
        // id could otherwise theoretically collide once hashed).
        var lockKey = groupId != null ? GROUP_LOCK_PREFIX + groupId : SINGLE_LOCK_PREFIX + id;
        boolean owns =
                Boolean.TRUE.equals(em.createNativeQuery("select pg_try_advisory_xact_lock(hashtextextended(?1, 0))")
                        .setParameter(1, lockKey)
                        .getSingleResult());
        if (!owns) {
            // Another tick already owns this group/booking this round -- it will process
            // (or has already processed) it under its own advisory lock. We hold NO row
            // lock on this candidate, so skipping here can never block or deadlock anyone.
            return;
        }

        // We now exclusively own this group/booking for the round. Nobody else can be
        // concurrently declining it, but its status may have changed since the lock-free
        // SELECT above -- either a sibling candidate row of the SAME group earlier in THIS
        // batch already declined it, or (single-booking case) it was handled by a fully
        // separate previous round. Re-check before acting.
        Booking b = Booking.findById(id);
        if (b.status != BookingStatus.PENDING) {
            return;
        }

        // Multi-host: the hold window elapsing on ANY row without full approval means the
        // whole conceptual meeting failed to convene -- decline every row in the group, not
        // just the one that happened to be claimed, and send a single declined email (fanned
        // out per host by EmailService) keyed on the group's lead row.
        if (b.groupId != null) {
            declineGroup(b, declined);
        } else {
            declineSingle(id, b, declined);
        }
    }

    private void declineGroup(Booking b, List<Long> declined) {
        Long creatorOwnerId = MeetingType.<MeetingType>findById(b.meetingTypeId).ownerId;
        Long leadId = Booking.leadOfGroup(b.groupId, creatorOwnerId).id;
        // Cheap idempotency guard (kept alongside the advisory lock, which is the primary
        // serialization point): the advisory lock only guarantees no OTHER tick is
        // concurrently declining this group THIS round -- the group could already have
        // been declined in an earlier round (e.g. a host action), so re-check the lead row.
        // PESSIMISTIC_WRITE is safe here: only the advisory-lock owner ever reaches this
        // line for a given group, and we hold no other row locks, so it can't cycle.
        Booking lead = Booking.findById(leadId, LockModeType.PESSIMISTIC_WRITE);
        if (lead.status == BookingStatus.DECLINED) {
            // Already processed -- nothing to do, and critically no second declined
            // email. NOTE: the lead row need not have been PENDING to begin with (e.g.
            // lead already CONFIRMED, a sibling still PENDING and expired) -- only
            // DECLINED means "already processed".
            return;
        }
        for (Booking r : Booking.<Booking>group(b.groupId)) {
            if (r.status == BookingStatus.DECLINED) {
                continue; // already declined (e.g. by a concurrent host action); idempotent skip
            }
            r.status = BookingStatus.DECLINED; // flipped while holding the group's advisory lock
            Reminder.deleteUnsentFor(r.id); // was ReminderScheduler.onDeclined observer
        }
        declined.add(lead.id);
        // Guard covers a render/load failure (throws before any persist): the flip +
        // cleanup still commit, one mail dropped. A crash (not caught) rolls back the
        // whole tx pre-commit and the row is reclaimed next tick -- the crash-safety
        // guarantee.
        try {
            emailService.enqueueDeclined(lead.id); // durable, same tx
        } catch (Exception ex) {
            Log.errorf(
                    ex,
                    "declined enqueue failed for group %s lead booking %d (declined, mail dropped)",
                    b.groupId,
                    lead.id);
        }
    }

    private void declineSingle(Long id, Booking b, List<Long> declined) {
        b.status = BookingStatus.DECLINED; // flipped while holding this booking's advisory lock
        Reminder.deleteUnsentFor(id); // was ReminderScheduler.onDeclined observer
        declined.add(id);
        // Guard covers a render/load failure (throws before any persist): the flip + cleanup
        // still commit, one mail dropped. A crash (not caught) rolls back the whole tx pre-commit
        // and the row is reclaimed next tick -- the crash-safety guarantee.
        try {
            emailService.enqueueDeclined(id); // was EmailService.onDeclined observer; durable, same tx
        } catch (Exception ex) {
            Log.errorf(ex, "declined enqueue failed for booking %d (declined, mail dropped)", id);
        }
    }
}
