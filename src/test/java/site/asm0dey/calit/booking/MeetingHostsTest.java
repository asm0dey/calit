package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        MeetingTypeHost c = MeetingTypeHost.of(t.id, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        c.persist();
        return t;
    }

    @Test
    @TestTransaction
    void notBookableUntilAllAccepted() {
        MeetingType t = multiHostType();
        assertThat(meetingHosts.bookable(t)).isFalse();
        MeetingTypeHost
            .forType(t.id)
            .forEach(h -> h.status = MeetingTypeHost.ACCEPTED);
        em.flush();
        assertThat(meetingHosts.bookable(t)).isTrue();
        assertThat(meetingHosts.hostOwnerIds(t)).hasSize(2);
    }

    @Test
    @TestTransaction
    void organizerPrefersCreatorThenLowestConnectedThenNull() {
        MeetingType t = multiHostType();
        // Accept the co-host row so hostOwnerIds(t) returns both hosts, exercising the
        // fallback loop (with only the PENDING row, hostOwnerIds would return just [1]).
        MeetingTypeHost
            .forType(t.id)
            .forEach(h -> h.status = MeetingTypeHost.ACCEPTED);
        em.flush();
        List<Long> hosts = meetingHosts.hostOwnerIds(t);
        assertThat(hosts).hasSize(2);
        var cohostId = hosts
            .stream()
            .filter(id -> !id.equals(1L))
            .findFirst()
            .orElseThrow();

        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(cohostId)).thenReturn(false);
        assertThat(meetingHosts.chooseOrganizer(t, hosts)).isOne();

        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(cohostId)).thenReturn(true);
        assertThat(meetingHosts.chooseOrganizer(t, hosts)).isEqualTo(cohostId);

        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        assertThat(meetingHosts.chooseOrganizer(t, hosts)).isNull();
    }

    @Test
    @TestTransaction
    void perHostBufferOverridesType() {
        MeetingType t = multiHostType();
        MeetingTypeHost creator = MeetingTypeHost.find(t.id, 1L);
        // override
        creator.bufferBeforeMinutes = 20;
        em.flush();
        // overridden
        assertThat(meetingHosts.effectiveBufferBefore(t, 1L, t.durationMinutes)).isEqualTo(20);
        // inherits type
        assertThat(meetingHosts.effectiveBufferAfter(t, 1L, t.durationMinutes)).isEqualTo(10);
    }

    @Test
    @TestTransaction
    void eligibleCohostCoversEveryBranch() {
        MeetingType t = MultiHostFixtures.meetingType(1L, "eligibility", 30);
        Long creatorOwnerId = 1L;

        AppUser disabled = MultiHostFixtures.enabledUser("disabled-user");
        disabled.enabled = false;
        em.flush();
        assertThat(meetingHosts.eligibleCohost(t.id, creatorOwnerId, disabled)).isFalse();

        AppUser incomplete = MultiHostFixtures.enabledUser("incomplete-user");
        incomplete.settingsComplete = false;
        em.flush();
        assertThat(meetingHosts.eligibleCohost(t.id, creatorOwnerId, incomplete)).isFalse();

        AppUser creator = AppUser.findById(creatorOwnerId);
        assertThat(meetingHosts.eligibleCohost(t.id, creatorOwnerId, creator)).isFalse();

        AppUser alreadyHost = MultiHostFixtures.enabledUser("already-host");
        MeetingTypeHost.of(t.id, alreadyHost.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING).persist();
        assertThat(meetingHosts.eligibleCohost(t.id, creatorOwnerId, alreadyHost)).isFalse();

        AppUser fresh = MultiHostFixtures.enabledUser("fresh-candidate");
        assertThat(meetingHosts.eligibleCohost(t.id, creatorOwnerId, fresh)).isTrue();
    }
}
