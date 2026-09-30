package site.asm0dey.calit.domain;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MeetingTypeTest {
    @Test
    @TestTransaction
    void persistsWithDefaultBuffersActiveAndNotSecret() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Intro 30";
        t.slug = "intro-30";
        t.durationMinutes = 30;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug(1L, "intro-30");
        assertThat(loaded.id).isEqualTo(t.id);
        assertThat(loaded.bufferBeforeMinutes).isZero();
        assertThat(loaded.bufferAfterMinutes).isZero();
        assertThat(loaded.active).isTrue();
        assertThat(loaded.secret).isFalse();
    }

    @Test
    @TestTransaction
    void findBySlugReturnsNullWhenMissing() {
        assertThat(MeetingType.findBySlug(1L, "does-not-exist")).isNull();
    }

    @Test
    @TestTransaction
    void listPublicExcludesSecretButFindBySlugStillReturnsIt() {
        MeetingType pub = new MeetingType();
        pub.ownerId = 1L;
        pub.name = "Public";
        pub.slug = "pub-listpublic";
        pub.durationMinutes = 30;
        pub.persist();

        MeetingType hidden = new MeetingType();
        hidden.ownerId = 1L;
        hidden.name = "Secret";
        hidden.slug = "secret-listpublic";
        hidden.durationMinutes = 30;
        hidden.secret = true;
        hidden.persist();

        List<MeetingType> publicList = MeetingType.listPublic(1L);
        assertThat(publicList
            .stream()
            .anyMatch(m -> "pub-listpublic".equals(m.slug))).isTrue();
        assertThat(publicList
            .stream()
            .anyMatch(m -> "secret-listpublic".equals(m.slug))).isFalse();
        // Direct slug access bypasses the public filter.
        assertThat(MeetingType.findBySlug(1L, "secret-listpublic").id).isEqualTo(hidden.id);
    }
}
