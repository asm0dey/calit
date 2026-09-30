package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingTypeHost;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class SlugCollisionTest {
    @Inject
    MeetingHosts meetingHosts;

    @Test
    @TestTransaction
    void addCohostRejectedWhenCandidateOwnsSameSlug() {
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        // Volodya already owns /volodya/intro
        MultiHostFixtures.meetingType(v.id, "intro", 30);
        // Pasha's shared intro
        MeetingType shared = MultiHostFixtures.meetingType(1L, "intro", 30);
        assertThatThrownBy(() -> meetingHosts.addCohost(shared, v)).asInstanceOf(throwable(IllegalStateException.class));
    }

    @Test
    @TestTransaction
    void slugUsedByOwnerExcludesSelf() {
        MeetingType t = MultiHostFixtures.meetingType(1L, "intro", 30);
        assertThat(MeetingType.slugUsedByOwner(1L, "intro", null)).isTrue();
        // exclude the type itself
        assertThat(MeetingType.slugUsedByOwner(1L, "intro", t.id)).isFalse();
    }

    @Test
    @TestTransaction
    void addCohostRejectedWhenCandidateCohostsDifferentTypeWithSameSlug() {
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        AppUser other = MultiHostFixtures.enabledUser("olga");
        // Volodya already co-hosts Olga's "intro" type
        MeetingType typeA = MultiHostFixtures.meetingType(other.id, "intro", 30);
        MeetingTypeHost.of(typeA.id, v.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();
        // Pasha's new shared intro
        MeetingType typeB = MultiHostFixtures.meetingType(1L, "intro", 30);
        assertThatThrownBy(() -> meetingHosts.assertSlugFreeForCohost(typeB, v))
            .asInstanceOf(throwable(IllegalStateException.class));
    }

    @Test
    @TestTransaction
    void assertSlugFreeForCohostAllowsDifferentSlugCohost() {
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        AppUser other = MultiHostFixtures.enabledUser("olga");
        // Volodya co-hosts Olga's "consult" type -- unrelated slug, must not block "intro"
        MeetingType typeA = MultiHostFixtures.meetingType(other.id, "consult", 30);
        MeetingTypeHost.of(typeA.id, v.id, MeetingTypeHost.COHOST, MeetingTypeHost.ACCEPTED).persist();

        MeetingType typeB = MultiHostFixtures.meetingType(1L, "intro", 30);
        assertDoesNotThrow(() -> meetingHosts.assertSlugFreeForCohost(typeB, v));
    }
}
