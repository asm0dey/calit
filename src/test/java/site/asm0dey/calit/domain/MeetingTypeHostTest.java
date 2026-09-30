package site.asm0dey.calit.domain;

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
class MeetingTypeHostTest {
    @Inject
    EntityManager em;

    @Test
    @TestTransaction
    void findsHostsAndDetectsMultiHost() {
        TestOwners.ensure(em, 1L);
        TestOwners.ensure(em, 2L);
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);

        MeetingTypeHost creator = MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED);
        creator.persist();
        MeetingTypeHost cohost = MeetingTypeHost.of(t.id, 2L, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING);
        cohost.consentToken = UUID.randomUUID();
        cohost.persist();

        assertThat(MeetingTypeHost.isMultiHost(t.id)).isTrue();
        assertThat(MeetingTypeHost.forType(t.id)).hasSize(2);
        assertThat(MeetingTypeHost.acceptedForType(t.id)).hasSize(1);
        assertThat(MeetingTypeHost.findByConsentToken(cohost.consentToken.toString())).isNotNull();
        assertThat(MeetingTypeHost.find(t.id, 2L).ownerId).isEqualTo(2L);
    }
}
