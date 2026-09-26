package site.asm0dey.calit.booking;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class OverlapConstraintTest {
    @Inject
    EntityManager em;

    private Booking held(long ownerId) {
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = 1L;
        b.inviteeName = "S";
        b.inviteeEmail = "s@x.com";
        b.startUtc = Instant.parse("2030-01-01T09:00:00Z");
        b.endUtc = Instant.parse("2030-01-01T09:30:00Z");
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        return b;
    }

    @Test
    @TestTransaction
    void differentOwnersMayOverlap() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        // seeds meeting_type.id=1 for booking's FK
        MultiHostFixtures.meetingType(1L, "solo", 30);
        held(1L).persist();
        held(2L).persist();
        // no exception -- different owners at same time is allowed
        em.flush();
    }

    @Test
    @TestTransaction
    void sameOwnerMayNotOverlap() {
        TestOwners.ensure(em, 1L);
        // seeds meeting_type.id=1 for booking's FK
        MultiHostFixtures.meetingType(1L, "solo", 30);
        held(1L).persist();
        // GenerationType.IDENTITY forces an immediate insert on persist() (can't batch identity
        // inserts), so the exclusion-constraint violation surfaces here rather than at em.flush().
        assertThrows(PersistenceException.class, () -> held(1L).persist());
    }
}
