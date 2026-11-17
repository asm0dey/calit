package site.asm0dey.calit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.test.MultiHostFixtures;

@QuarkusTest
class MeetingTypeHostRepositoryTest {
    @Inject
    MeetingTypeHostRepository hosts;

    @Test
    @TestTransaction
    void deleteCreatorRowLeavesCohosts() {
        var cohost = MultiHostFixtures.enabledUser("repo-cohost");
        var t = MultiHostFixtures.acceptedTwoHostType(1L, cohost.id, "repo-two-host", 30, false);

        assertThat(hosts.deleteCreatorRow(t.id)).isEqualTo(1L);
        assertThat(hosts.forType(t.id))
            .extracting(h -> h.ownerId, h -> h.role)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(cohost.id, MeetingTypeHost.COHOST));
    }

    @Test
    @TestTransaction
    void isMultiHostIgnoresCreatorRow() {
        var cohost = MultiHostFixtures.enabledUser("repo-cohost2");
        var t = MultiHostFixtures.meetingType(1L, "repo-creator-only", 30);
        MeetingTypeHost.of(t.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();

        assertThat(hosts.isMultiHost(t.id)).isFalse();
        MeetingTypeHost.of(t.id, cohost.id, MeetingTypeHost.COHOST, MeetingTypeHost.PENDING).persist();
        assertThat(hosts.isMultiHost(t.id)).isTrue();
    }

    @Test
    @TestTransaction
    void forTypesReturnsOnlyTheListedTypesRows() {
        var cohost = MultiHostFixtures.enabledUser("repo-cohost3");
        var a = MultiHostFixtures.acceptedTwoHostType(1L, cohost.id, "repo-for-types-a", 30, false);
        var b = MultiHostFixtures.meetingType(1L, "repo-for-types-b", 30);
        MeetingTypeHost.of(b.id, 1L, MeetingTypeHost.CREATOR, MeetingTypeHost.ACCEPTED).persist();
        var other = MultiHostFixtures.acceptedTwoHostType(1L, cohost.id, "repo-for-types-other", 30, false);

        assertThat(hosts.forTypes(java.util.List.of(a.id, b.id)))
            .extracting(h -> h.meetingTypeId)
            .containsExactlyInAnyOrder(a.id, a.id, b.id)
            .doesNotContain(other.id);
    }
}
