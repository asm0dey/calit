package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * Task 14: reminders and pending-expiry must be group-aware. A group's reminder belongs to the
 * lead row only (no N reminders for one conceptual meeting); an expiry tick on any still-PENDING
 * row of a group declines the WHOLE group and sends exactly one declined email (fanned out by
 * {@link site.asm0dey.calit.email.EmailService#enqueueDeclined} per host, keyed on the lead row).
 */
@QuarkusTest
class GroupSchedulerTest {
    // DatabaseResetCallback baseline admin
    private static final long CREATOR_ID = 1L;
    private static final long COHOST_ID = 2L;
    private static final String INVITEE_EMAIL = "invitee-groupsched@example.com";
    @Inject
    ReminderScheduler reminderScheduler;
    @Inject
    PendingExpiryScheduler expiryScheduler;
    @Inject
    EntityManager em;

    private MeetingType twoHostType(boolean requiresApproval) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost-sched");
            MultiHostFixtures.settings(CREATOR_ID, "Creator");
            MultiHostFixtures.settings(COHOST_ID, "Cohost");
            return MultiHostFixtures.acceptedTwoHostType(CREATOR_ID, COHOST_ID, "group-sched", 30, requiresApproval);
        });
    }

    private record GroupIds(long leadId, long cohostId) {}

    private GroupIds seedGroup(
            long meetingTypeId,
            Instant start,
            BookingStatus leadStatus,
            Instant leadCreatedAt,
            BookingStatus cohostStatus,
            Instant cohostCreatedAt
    ) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var groupId = UUID.randomUUID();
            long leadId = seedRow(meetingTypeId, CREATOR_ID, groupId, start, leadStatus, leadCreatedAt);
            long cohostId = seedRow(meetingTypeId, COHOST_ID, groupId, start, cohostStatus, cohostCreatedAt);
            return new GroupIds(leadId, cohostId);
        });
    }

    private long seedRow(
            long meetingTypeId,
            long ownerId,
            UUID groupId,
            Instant start,
            BookingStatus status,
            Instant createdAt
    ) {
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = meetingTypeId;
        b.inviteeName = "Sam Invitee";
        b.inviteeEmail = INVITEE_EMAIL;
        b.startUtc = start;
        b.endUtc = start.plus(30, ChronoUnit.MINUTES);
        b.status = status;
        b.groupId = groupId;
        b.manageToken = "tok-" + ownerId + "-" + System.nanoTime();
        b.createdAt = createdAt;
        b.persist();
        return b.id;
    }

    @Test
    void onlyLeadRowGetsReminderOnGroupConfirm() {
        MeetingType type = twoHostType(false);
        // well beyond the default 1440-min lead
        var start = Instant.now().plus(500, ChronoUnit.HOURS);
        GroupIds ids =
                seedGroup(
                        type.id,
                        start,
                        BookingStatus.CONFIRMED,
                        Instant.now(),
                        BookingStatus.CONFIRMED,
                        Instant.now()
        );
        // Simulate both rows firing scheduleReminder -- BookingConfirmed only fires once with the
        // lead id in practice, but the guard must hold even if a non-lead row's event somehow fires.
        reminderScheduler.scheduleReminder(ids.leadId());
        reminderScheduler.scheduleReminder(ids.cohostId());

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, Reminder.count("bookingId", ids.leadId()), "lead row gets exactly one reminder");
            assertEquals(0, Reminder.count("bookingId", ids.cohostId()), "non-lead row never gets its own reminder");
        });
    }

    @Test
    void groupExpiryDeclinesWholeGroupWhenOneRowStillPendingAtDeadline() {
        MeetingType type = twoHostType(true);
        var start = Instant.now().plus(500, ChronoUnit.HOURS);
        GroupIds ids = seedGroup(
                type.id,
                start,
                BookingStatus.CONFIRMED,
                Instant
                    .now()
                    // lead already approved
                    .minus(2, ChronoUnit.HOURS),
                BookingStatus.PENDING,
                Instant
                    .now()
                    // cohost still pending, past the 24h hold
                    .minus(25, ChronoUnit.HOURS)
        );

        expiryScheduler.expirePendingBookings();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(
                    BookingStatus.DECLINED,
                    ((Booking) Booking.findById(ids.leadId())).status,
                    "already-approved lead row is also declined -- the whole group dies together"
            );
            assertEquals(
                    BookingStatus.DECLINED,
                    ((Booking) Booking.findById(ids.cohostId())).status,
                    "expired PENDING row is declined"
            );
            assertEquals(1, EmailOutbox.count("recipient", INVITEE_EMAIL), "invitee gets exactly one declined email");
            assertEquals(
                    1,
                    EmailOutbox.count("recipient", "Creator@x.com"),
                    "lead host gets exactly one declined email"
            );
            assertEquals(1, EmailOutbox.count("recipient", "Cohost@x.com"), "co-host gets exactly one declined email");
        });
    }

    /**
     * Deterministic (no real threads) regression for the Task 14 review finding: two ticks racing
     * on DIFFERENT sibling rows of the SAME group must not both run the group-decline branch and
     * both enqueue a declined email. We simulate "another tick already committed the group decline"
     * by pre-setting the LEAD row to DECLINED directly, while leaving the co-host row PENDING and
     * past its hold window so the claim query still picks it up and the expiry tick still enters
     * the group branch. The lead-row PESSIMISTIC_WRITE lock check must see lead.status == DECLINED
     * and skip -- no re-decline, no second email, co-host row left untouched.
     */
    @Test
    void groupExpirySkipsAndSendsNoEmailWhenLeadAlreadyDeclinedByAnotherTick() {
        MeetingType type = twoHostType(true);
        var start = Instant.now().plus(500, ChronoUnit.HOURS);
        GroupIds ids = seedGroup(
                type.id,
                start,
                // simulates a concurrent tick that already won the race
                BookingStatus.DECLINED,
                Instant.now().minus(25, ChronoUnit.HOURS),
                BookingStatus.PENDING,
                Instant
                    .now()
                    // still claimable: past the 24h hold
                    .minus(25, ChronoUnit.HOURS)
        );
        // email_outbox isn't reset per test (DatabaseResetCallback only truncates domain tables), so
        // compare counts before/after the tick rather than asserting an absolute value.
        record Baseline(long invitee, long lead, long cohost) {}
        Baseline before = QuarkusTransaction
            .requiringNew()
            .call(() -> new Baseline(
                    EmailOutbox.count("recipient", INVITEE_EMAIL),
                    EmailOutbox.count("recipient", "Creator@x.com"),
                    EmailOutbox.count("recipient", "Cohost@x.com")
            ));

        expiryScheduler.expirePendingBookings();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(
                    BookingStatus.DECLINED,
                    ((Booking) Booking.findById(ids.leadId())).status,
                    "lead row stays DECLINED (already handled by the other tick)"
            );
            assertEquals(
                    BookingStatus.PENDING,
                    ((Booking) Booking.findById(ids.cohostId())).status,
                    "co-host row is left untouched -- the skip happens before the group loop runs"
            );
            assertEquals(
                    before.invitee(),
                    EmailOutbox.count("recipient", INVITEE_EMAIL),
                    "no second declined email for the invitee"
            );
            assertEquals(
                    before.lead(),
                    EmailOutbox.count("recipient", "Creator@x.com"),
                    "no second declined email for the lead host"
            );
            assertEquals(
                    before.cohost(),
                    EmailOutbox.count("recipient", "Cohost@x.com"),
                    "no second declined email for the co-host"
            );
        });
    }

    /**
     * Deterministic (no real threads) regression proving the per-group advisory-lock
     * serialization from the AB-BA deadlock fix: a tick must SKIP a claimed row of a group whose
     * advisory lock is already held by another still-open transaction, rather than trying to lock
     * (and blocking/deadlocking on) that group's rows. We simulate "another tick already owns this
     * group's decline this round" by manually opening a real, still-uncommitted transaction on this
     * thread and taking the group's {@code pg_try_advisory_xact_lock} in it first -- the lock is
     * session-scoped in Postgres, so {@link PendingExpiryScheduler}'s own {@code requiringNew()}
     * transaction (a different connection) sees it held and must back off. The claimed row --
     * expired PENDING, otherwise indistinguishable from the "normal decline" case -- must be left
     * completely untouched, and neither host nor the invitee gets an email.
     */
    @Test
    void groupExpirySkipsClaimedRowWhenGroupAdvisoryLockIsHeldByAnotherTransaction() {
        MeetingType type = twoHostType(true);
        var start = Instant.now().plus(500, ChronoUnit.HOURS);
        GroupIds ids = seedGroup(
                type.id,
                start,
                BookingStatus.CONFIRMED,
                Instant
                    .now()
                    // lead already approved
                    .minus(2, ChronoUnit.HOURS),
                BookingStatus.PENDING,
                Instant
                    .now()
                    // cohost still pending, past the 24h hold
                    .minus(25, ChronoUnit.HOURS)
        );

        UUID groupId = QuarkusTransaction
            .requiringNew()
            .call(() -> ((Booking) Booking.findById(ids.leadId())).groupId);

        record Baseline(long invitee, long lead, long cohost) {}
        Baseline before = QuarkusTransaction
            .requiringNew()
            .call(() -> new Baseline(
                    EmailOutbox.count("recipient", INVITEE_EMAIL),
                    EmailOutbox.count("recipient", "Creator@x.com"),
                    EmailOutbox.count("recipient", "Cohost@x.com")
            ));
        // Manually open (and hold open) a transaction that owns the group's advisory lock, standing
        // in for "another tick's still-open transaction". PendingExpiryScheduler's own
        // requiringNew() call below runs on a DIFFERENT connection, so its
        // pg_try_advisory_xact_lock attempt for the same group must return false.
        QuarkusTransaction.begin();
        try {
            var acquiredHere = (Boolean) em
                .createNativeQuery("select pg_try_advisory_xact_lock(hashtextextended(?1, 0))")
                .setParameter(1, "group:" + groupId)
                .getSingleResult();
            assertEquals(Boolean.TRUE, acquiredHere, "test setup: this transaction must win the lock first");

            expiryScheduler.expirePendingBookings();
        } finally {
            QuarkusTransaction.rollback();
        }

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(
                    BookingStatus.CONFIRMED,
                    ((Booking) Booking.findById(ids.leadId())).status,
                    "lead row untouched -- the tick never owned the group's advisory lock"
            );
            assertEquals(
                    BookingStatus.PENDING,
                    ((Booking) Booking.findById(ids.cohostId())).status,
                    "claimed cohost row is skipped outright, not declined, while the lock is held elsewhere"
            );
        });
        assertEquals(
                before.invitee(),
                EmailOutbox.count("recipient", INVITEE_EMAIL),
                "no declined email for the invitee"
        );
        assertEquals(
                before.lead(),
                EmailOutbox.count("recipient", "Creator@x.com"),
                "no declined email for the lead host"
        );
        assertEquals(
                before.cohost(),
                EmailOutbox.count("recipient", "Cohost@x.com"),
                "no declined email for the co-host"
        );
    }

    /**
     * Single-host counterpart of {@link
     * #groupExpirySkipsClaimedRowWhenGroupAdvisoryLockIsHeldByAnotherTransaction}: a claimed,
     * expired PENDING single-host booking (no {@code groupId}, so the scheduler keys its advisory
     * lock as {@code booking:<id>} rather than {@code group:<id>}) must be SKIPPED -- not declined,
     * no email -- when that lock is already held by another still-open transaction. Same
     * technique: manually open and hold a real transaction on this thread that wins the lock
     * first, so {@link PendingExpiryScheduler}'s own {@code requiringNew()} transaction (a
     * different connection) must back off.
     */
    @Test
    void singleHostExpirySkipsClaimedRowWhenBookingAdvisoryLockIsHeldByAnotherTransaction() {
        MeetingType type = twoHostType(true);
        var start = Instant.now().plus(500, ChronoUnit.HOURS);
        long bookingId = QuarkusTransaction
            .requiringNew()
            .call(() -> seedRow(
                    type.id,
                    CREATOR_ID,
                    null,
                    start,
                    BookingStatus.PENDING,
                    Instant.now().minus(25, ChronoUnit.HOURS)
            ));

        long invitesBefore = QuarkusTransaction
            .requiringNew()
            .call(() -> EmailOutbox.count("recipient", INVITEE_EMAIL));
        // Manually open (and hold open) a transaction that owns this booking's advisory lock,
        // standing in for "another tick's still-open transaction" -- mirrors the group-lock test.
        QuarkusTransaction.begin();
        try {
            var acquiredHere = (Boolean) em
                .createNativeQuery("select pg_try_advisory_xact_lock(hashtextextended(?1, 0))")
                .setParameter(1, "booking:" + bookingId)
                .getSingleResult();
            assertEquals(Boolean.TRUE, acquiredHere, "test setup: this transaction must win the lock first");

            expiryScheduler.expirePendingBookings();
        } finally {
            QuarkusTransaction.rollback();
        }

        QuarkusTransaction
            .requiringNew()
            .run(() -> assertEquals(
                    BookingStatus.PENDING,
                    ((Booking) Booking.findById(bookingId)).status,
                    "claimed single-host row is skipped outright, not declined, while its advisory lock is held elsewhere"
            ));
        assertEquals(
                invitesBefore,
                EmailOutbox.count("recipient", INVITEE_EMAIL),
                "no declined email for the invitee -- the row was never declined"
        );
    }
}
