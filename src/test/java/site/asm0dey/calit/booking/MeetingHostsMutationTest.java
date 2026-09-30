package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.HostConsentRequested;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class MeetingHostsMutationTest {
    @Inject
    MeetingHosts meetingHosts;
    @Inject
    EntityManager em;
    static final AtomicInteger CONSENTS = new AtomicInteger();

    void onConsent(@Observes HostConsentRequested e) {
        CONSENTS.incrementAndGet();
    }

    @Test
    @TestTransaction
    void addCohostIsIdempotentAndCreatesCreatorRow() {
        CONSENTS.set(0);
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        meetingHosts.addCohost(t, v);
        // idempotent -- still one pending row, one email
        meetingHosts.addCohost(t, v);
        em.flush();
        // creator + one cohost
        assertThat(MeetingTypeHost.forType(t.id)).hasSize(2);
        assertThat(CONSENTS.get()).isOne();
        assertThat(MeetingTypeHost.find(t.id, 1L).role).isEqualTo(MeetingTypeHost.CREATOR);
    }

    @Test
    @TestTransaction
    void removeLastCohostRevertsToSingleHost() {
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        meetingHosts.addCohost(t, v);
        em.flush();
        meetingHosts.removeHost(t, v.id);
        em.flush();
        // creator row gone too
        assertThat(MeetingTypeHost.forType(t.id)).hasSize(0);
        assertThat(MeetingTypeHost.isMultiHost(t.id)).isFalse();
    }

    @Test
    @TestTransaction
    void capRejectsEleventhHost() {
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);
        for (var i = 0; i < 9; i++) {
            meetingHosts.addCohost(t, MultiHostFixtures.enabledUser("h" + i));
        }
        // 1 creator + 9 cohosts = 10
        em.flush();
        AppUser extra = MultiHostFixtures.enabledUser("overflow");
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> meetingHosts.addCohost(t, extra));
    }
}
