package site.asm0dey.calit.booking;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class BookingGroupQueryTest {
    @Inject
    EntityManager em;

    /**
     * {@code slot} picks a distinct, non-overlapping 30-min window per row. The DB-level
     * {@code booking_no_overlap_held} exclusion constraint (V4) is still instance-global at this
     * point in the branch (owner-scoping it is Task 3 / V22) — a real multi-host booking's per-host
     * rows share one time range, but until V22 lands two different owners can't hold the identical
     * raw range in the same test. Distinct slots sidestep that without touching V22 out of order.
     */
    private Booking row(UUID group, long ownerId, int slot) {
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = 1L;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@x.com";
        b.startUtc = Instant.parse("2030-01-01T09:00:00Z").plusSeconds(slot * 3600L);
        b.endUtc = b.startUtc.plusSeconds(1800L);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.groupId = group;
        b.persist();
        return b;
    }

    @Test
    @TestTransaction
    void groupAndLeadResolve() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        // seeds meeting_type.id=1 for booking's FK
        MultiHostFixtures.meetingType(1L, "solo", 30);
        var g = UUID.randomUUID();
        // creator/lead
        row(g, 1L, 0);
        // cohost
        row(g, 2L, 1);
        assertEquals(2, Booking.group(g).size());
        assertEquals(1L, Booking.leadOfGroup(g, 1L).ownerId);
    }

    @Test
    @TestTransaction
    void abuseCapCountsPerGroupNotPerRow() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        // seeds meeting_type.id=1 for booking's FK
        MultiHostFixtures.meetingType(1L, "solo", 30);
        var g = UUID.randomUUID();
        row(g, 1L, 0);
        // one multi-host booking = 2 rows
        row(g, 2L, 1);
        // one single-host booking = 1 row
        row(null, 1L, 2);
        long n = Booking.countDistinctBookingsByEmailBetween(
                "sam@x.com",
                Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2100-01-01T00:00:00Z")
        );
        // 2 conceptual bookings, not 3 rows
        assertEquals(2, n);
    }
}
