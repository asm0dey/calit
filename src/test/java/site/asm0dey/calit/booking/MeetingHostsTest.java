package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class MeetingHostsTest {

    @Inject
    MeetingHosts meetingHosts;

    @Inject
    EntityManager em;

    @InjectMock
    CalendarPort calendarPort;

    private MeetingType multiHostType() {
        // admin id 1 is the creator; make a second enabled user as co-host.
        AppUser cohost = MultiHostFixtures.enabledUser("volodya");
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);
        t.bufferBeforeMinutes = 5;
        t.bufferAfterMinutes = 10;
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED)
                .persist();
        MeetingTypeHost c = MeetingTypeHost.of(t.id, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        c.persist();
        return t;
    }

    @Test
    @TestTransaction
    void notBookableUntilAllAccepted() {
        MeetingType t = multiHostType();
        assertFalse(meetingHosts.bookable(t));
        MeetingTypeHost.forType(t.id).forEach(h -> h.status = MeetingTypeHost.ACCEPTED);
        em.flush();
        assertTrue(meetingHosts.bookable(t));
        assertEquals(2, meetingHosts.hostOwnerIds(t).size());
    }

    @Test
    @TestTransaction
    void organizerPrefersCreatorThenLowestConnectedThenNull() {
        MeetingType t = multiHostType();
        // Accept the co-host row so hostOwnerIds(t) returns both hosts, exercising the
        // fallback loop (with only the PENDING row, hostOwnerIds would return just [1]).
        MeetingTypeHost.forType(t.id).forEach(h -> h.status = MeetingTypeHost.ACCEPTED);
        em.flush();
        List<Long> hosts = meetingHosts.hostOwnerIds(t);
        assertEquals(2, hosts.size());
        var cohostId = hosts.stream().filter(id -> !id.equals(1L)).findFirst().orElseThrow();

        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(cohostId)).thenReturn(false);
        assertEquals(1L, meetingHosts.chooseOrganizer(t, hosts));

        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        assertEquals(cohostId, meetingHosts.chooseOrganizer(t, hosts));

        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        assertNull(meetingHosts.chooseOrganizer(t, hosts));
    }

    @Test
    @TestTransaction
    void perHostBufferOverridesType() {
        MeetingType t = multiHostType();
        MeetingTypeHost creator = MeetingTypeHost.find(t.id, 1L);
        creator.bufferBeforeMinutes = 20; // override
        em.flush();
        assertEquals(20, meetingHosts.effectiveBufferBefore(t, 1L, t.durationMinutes)); // overridden
        assertEquals(10, meetingHosts.effectiveBufferAfter(t, 1L, t.durationMinutes)); // inherits type
    }

    @Test
    @TestTransaction
    void eligibleCohostCoversEveryBranch() {
        MeetingType t = MultiHostFixtures.meetingType(1L, "eligibility", 30);
        Long creatorOwnerId = 1L;

        AppUser disabled = MultiHostFixtures.enabledUser("disabled-user");
        disabled.enabled = false;
        em.flush();
        assertFalse(meetingHosts.eligibleCohost(t.id, creatorOwnerId, disabled));

        AppUser incomplete = MultiHostFixtures.enabledUser("incomplete-user");
        incomplete.settingsComplete = false;
        em.flush();
        assertFalse(meetingHosts.eligibleCohost(t.id, creatorOwnerId, incomplete));

        AppUser creator = AppUser.findById(creatorOwnerId);
        assertFalse(meetingHosts.eligibleCohost(t.id, creatorOwnerId, creator));

        AppUser alreadyHost = MultiHostFixtures.enabledUser("already-host");
        MeetingTypeHost.of(t.id, alreadyHost.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING)
                .persist();
        assertFalse(meetingHosts.eligibleCohost(t.id, creatorOwnerId, alreadyHost));

        AppUser fresh = MultiHostFixtures.enabledUser("fresh-candidate");
        assertTrue(meetingHosts.eligibleCohost(t.id, creatorOwnerId, fresh));
    }
}
