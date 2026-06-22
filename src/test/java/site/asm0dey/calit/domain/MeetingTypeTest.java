package site.asm0dey.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(t.id, loaded.id);
        assertEquals(0, loaded.bufferBeforeMinutes);
        assertEquals(0, loaded.bufferAfterMinutes);
        assertEquals(true, loaded.active);
        assertEquals(false, loaded.secret);
    }

    @Test
    @TestTransaction
    void findBySlugReturnsNullWhenMissing() {
        assertNull(MeetingType.findBySlug(1L, "does-not-exist"));
    }

    @Test
    @TestTransaction
    void listPublicExcludesSecretButFindBySlugStillReturnsIt() {
        MeetingType pub = new MeetingType();
        pub.ownerId = 1L;
        pub.name = "Public"; pub.slug = "pub-listpublic"; pub.durationMinutes = 30;
        pub.persist();

        MeetingType hidden = new MeetingType();
        hidden.ownerId = 1L;
        hidden.name = "Secret"; hidden.slug = "secret-listpublic"; hidden.durationMinutes = 30;
        hidden.secret = true;
        hidden.persist();

        List<MeetingType> publicList = MeetingType.listPublic(1L);
        assertTrue(publicList.stream().anyMatch(m -> "pub-listpublic".equals(m.slug)));
        assertFalse(publicList.stream().anyMatch(m -> "secret-listpublic".equals(m.slug)));
        // Direct slug access bypasses the public filter.
        assertEquals(hidden.id, MeetingType.findBySlug(1L, "secret-listpublic").id);
    }
}
