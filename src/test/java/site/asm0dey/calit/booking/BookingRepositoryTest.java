package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.test.MultiHostFixtures;

@QuarkusTest
class BookingRepositoryTest {
    private static final Instant FUTURE = Instant.parse("2030-01-01T10:00:00Z");
    @Inject
    BookingRepository bookings;

    @Test
    void repositoryLoadsActiveRecordEntity() {
        var token = UUID.randomUUID().toString();
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            var b = row(1L, type(1L, "repo-load").id, FUTURE, BookingStatus.CONFIRMED);
            b.manageToken = token;
            b.persist();
            return b.id;
        });
        QuarkusTransaction
            .requiringNew()
            .run(() -> assertThat(bookings.findById(id))
                .extracting(b -> b.manageToken)
                .isEqualTo(token));
    }

    @Test
    void lockForDecisionKeepsUnflushedChanges() {
        Long id = QuarkusTransaction
            .requiringNew()
            .call(() -> persisted(row(1L, type(1L, "lock-unflushed").id, FUTURE, BookingStatus.PENDING)).id);
        QuarkusTransaction.requiringNew().run(() -> {
            bookings.findById(id).title = "changed before the lock";
            bookings.lockForDecision(id);
        });
        QuarkusTransaction
            .requiringNew()
            .run(() -> assertThat(bookings.findById(id).title).isEqualTo("changed before the lock"));
    }

    @Test
    void lockForDecisionOnSetRowReturnsClickedRow() {
        // committed first: a row inserted in this transaction already carries Hibernate's write lock
        var ids = committedSet("lock-set");
        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                assertThat(bookings.lockForDecision(ids.get(1)))
                    .extracting(b -> b.id)
                    .isEqualTo(ids.get(1));
                // a click on any set row takes the one lock on the set's lead, not on the clicked row
                assertLockedLeadOnly(ids);
            });
    }

    @Test
    void lockSetLeadLocksTheCreatorsEarliestRow() {
        var ids = committedSet("lock-lead");
        QuarkusTransaction.requiringNew().run(() -> {
            var set = bookings.findById(ids.getFirst()).setId;
            assertThat(bookings.lockSetLead(set, 1L))
                .extracting(b -> b.id)
                .isEqualTo(ids.getFirst());
            assertLockedLeadOnly(ids);
        });
    }

    /**
     * A committed two-row set: the lead first, then a later row.
     */
    private static List<Long> committedSet(String slug) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var t = type(1L, slug);
            var set = UUID.randomUUID();
            var later = row(1L, t.id, FUTURE.plus(Duration.ofDays(1)), BookingStatus.PENDING);
            later.setId = set;
            persisted(later);
            var lead = row(1L, t.id, FUTURE, BookingStatus.PENDING);
            lead.setId = set;
            persisted(lead);
            return List.of(lead.id, later.id);
        });
    }

    private void assertLockedLeadOnly(List<Long> ids) {
        var em = bookings.getEntityManager();
        assertThat(em.getLockMode(bookings.findById(ids.getFirst()))).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(em.getLockMode(bookings.findById(ids.get(1)))).isNotEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @TestTransaction
    void lockForDecisionReturnsNullForUnknownId() {
        assertThat(bookings.lockForDecision(999_999L)).isNull();
    }

    @Test
    @TestTransaction
    void countDistinctCountsGroupAndSetOnce() {
        var t = type(1L, "count-distinct");
        persisted(row(1L, t.id, FUTURE, BookingStatus.CONFIRMED));
        var group = UUID.randomUUID();
        // one group row per host: same-owner rows at one time would break booking_no_overlap_held
        for (Long host : List.of(1L, MultiHostFixtures.enabledUser("repo-cohost").id)) {
            var b = row(host, t.id, FUTURE.plus(Duration.ofHours(1)), BookingStatus.CONFIRMED);
            b.groupId = group;
            persisted(b);
        }
        var set = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            var b = row(1L, t.id, FUTURE.plus(Duration.ofDays(i + 1)), BookingStatus.PENDING);
            b.setId = set;
            persisted(b);
        }
        var now = Instant.now();

        assertThat(bookings.countDistinctBookingsByEmailBetween(
                "ada@example.com",
                now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1))
        ))
            .isEqualTo(3L);
    }

    @Test
    @TestTransaction
    void futureHeldGroupRowsExcludesPastCancelledAndStandalone() {
        var t = type(1L, "future-held");
        var now = Instant.now();
        var held = grouped(row(1L, t.id, now.plus(Duration.ofDays(1)), BookingStatus.CONFIRMED));
        grouped(row(1L, t.id, now.minus(Duration.ofDays(1)), BookingStatus.CONFIRMED));
        grouped(row(1L, t.id, now.plus(Duration.ofDays(2)), BookingStatus.CANCELLED));
        persisted(row(1L, t.id, now.plus(Duration.ofDays(3)), BookingStatus.CONFIRMED));

        assertThat(bookings.futureHeldGroupRows(1L, t.id, now))
            .extracting(b -> b.id)
            .containsExactly(held.id);
        assertThat(bookings.countFutureHeldGroupRows(1L, t.id, now)).isEqualTo(1L);
    }

    @Test
    @TestTransaction
    void heldOverlappingIsOwnerScoped() {
        var other = MultiHostFixtures.enabledUser("repo-other");
        var mine = persisted(row(1L, type(1L, "overlap-mine").id, FUTURE, BookingStatus.CONFIRMED));
        persisted(row(other.id, type(other.id, "overlap-theirs").id, FUTURE, BookingStatus.CONFIRMED));

        assertThat(bookings.heldOverlapping(1L, FUTURE.minus(Duration.ofHours(1)), FUTURE.plus(Duration.ofHours(1))))
            .extracting(b -> b.id)
            .containsExactly(mine.id);
    }

    private static MeetingType type(Long ownerId, String slug) {
        return MultiHostFixtures.meetingType(ownerId, slug, 30);
    }

    private static Booking persisted(Booking b) {
        b.persist();
        return b;
    }

    private static Booking grouped(Booking b) {
        b.groupId = UUID.randomUUID();
        return persisted(b);
    }

    private static Booking row(Long ownerId, Long typeId, Instant start, BookingStatus status) {
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = typeId;
        b.inviteeName = "Ada";
        b.inviteeEmail = "ada@example.com";
        b.startUtc = start;
        b.endUtc = start.plus(Duration.ofMinutes(30));
        b.status = status;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        return b;
    }
}
