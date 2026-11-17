package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class MeetingHostsTest {
    @Inject
    MeetingHosts meetingHosts;
    @Inject
    EntityManager em;

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
}
