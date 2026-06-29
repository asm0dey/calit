package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class AvailabilityRuleOwnerScopeTest {

    @Inject
    EntityManager em;

    private AvailabilityRule globalRule(Long owner, DayOfWeek dow) {
        TestOwners.ensure(em, owner);
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = owner;
        r.meetingTypeId = null;
        r.dayOfWeek = dow;
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(17, 0);
        r.persist();
        return r;
    }

    @Test
    @TestTransaction
    void globalForOwnerReturnsOnlyThatOwnersGlobalRules() {
        globalRule(4001L, DayOfWeek.MONDAY);
        globalRule(4002L, DayOfWeek.MONDAY); // other owner, same day
        // A per-type rule for owner A must NOT show up in the global list. meeting_type_id is a real
        // FK, so persist a meeting type for owner 4001 and use its id.
        MeetingType mt = new MeetingType();
        mt.ownerId = 4001L;
        mt.name = "Typed";
        mt.slug = "avail-scope-typed";
        mt.durationMinutes = 30;
        mt.persist();
        AvailabilityRule typed = new AvailabilityRule();
        typed.ownerId = 4001L;
        typed.meetingTypeId = mt.id;
        typed.dayOfWeek = DayOfWeek.MONDAY;
        typed.startTime = LocalTime.of(8, 0);
        typed.endTime = LocalTime.of(9, 0);
        typed.persist();

        assertEquals(1, AvailabilityRule.globalForOwner(4001L, DayOfWeek.MONDAY).size());
        assertEquals(1, AvailabilityRule.globalForOwner(4002L, DayOfWeek.MONDAY).size());
        assertEquals(
                0, AvailabilityRule.globalForOwner(4001L, DayOfWeek.TUESDAY).size());
    }

    @Test
    @TestTransaction
    void dateOverrideGlobalResolveIsOwnerScoped() {
        var day = LocalDate.of(2026, 7, 1);
        TestOwners.ensure(em, 4001L);
        TestOwners.ensure(em, 4002L);
        DateOverride a = new DateOverride();
        a.ownerId = 4001L;
        a.meetingTypeId = null;
        a.overrideDate = day;
        a.persist();
        DateOverride b = new DateOverride();
        b.ownerId = 4002L;
        b.meetingTypeId = null;
        b.overrideDate = day;
        b.persist();

        // No per-type override -> falls back to the OWNER's global override, never the other owner's.
        assertEquals(a.id, DateOverride.resolve(4001L, 9999L, day).id);
        assertEquals(b.id, DateOverride.resolve(4002L, 9999L, day).id);
        assertNull(DateOverride.resolve(4003L, 9999L, day), "owner with no override -> null");
    }
}
