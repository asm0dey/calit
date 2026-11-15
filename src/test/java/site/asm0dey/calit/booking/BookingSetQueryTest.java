package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class BookingSetQueryTest {
    private static final Instant FROM = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2100-01-01T00:00:00Z");
    @Inject
    EntityManager em;

    private Booking row(UUID set, UUID group, long ownerId, int slot) {
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
        b.setId = set;
        b.groupId = group;
        b.persist();
        return b;
    }

    private void seed() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        MultiHostFixtures.meetingType(1L, "solo", 30);
    }

    @Test
    @TestTransaction
    void setCountsOnceTowardDailyCap() {
        seed();
        var s = UUID.randomUUID();
        row(s, null, 1L, 0);
        row(s, null, 1L, 1);
        row(s, null, 1L, 2);
        row(null, null, 1L, 3);
        assertThat(Booking.countDistinctBookingsByEmailBetween("sam@x.com", FROM, TO)).isEqualTo(2);
    }

    @Test
    @TestTransaction
    void setOfCoHostedBookingsCountsOnce() {
        seed();
        var s = UUID.randomUUID();
        var g1 = UUID.randomUUID();
        var g2 = UUID.randomUUID();
        row(s, g1, 1L, 0);
        row(s, g1, 2L, 1);
        row(s, g2, 1L, 2);
        row(s, g2, 2L, 3);
        assertThat(Booking.countDistinctBookingsByEmailBetween("sam@x.com", FROM, TO)).isEqualTo(1);
    }

    @Test
    @TestTransaction
    void leadOfSetIsCreatorsEarliestRow() {
        seed();
        var s = UUID.randomUUID();
        row(s, null, 2L, 0);
        var later = row(s, null, 1L, 5);
        var earliest = row(s, null, 1L, 2);
        assertThat(Booking.set(s))
            .extracting(b -> b.id)
            .hasSize(3)
            .doesNotHaveDuplicates();
        assertThat(Booking.set(s).getFirst().ownerId).isEqualTo(2L);
        assertThat(Booking.leadOfSet(s, 1L).id).isEqualTo(earliest.id).isNotEqualTo(later.id);
    }
}
