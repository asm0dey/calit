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
        assertThat(publicList)
            .extracting(m -> m.slug)
            .contains("pub-listpublic");
        assertThat(publicList)
            .extracting(m -> m.slug)
            .doesNotContain("secret-listpublic");
        // Direct slug access bypasses the public filter.
        assertThat(MeetingType.findBySlug(1L, "secret-listpublic").id).isEqualTo(hidden.id);
    }
}
