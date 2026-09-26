package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class MeetingTypeOwnerScopeTest {
    @Inject
    EntityManager em;

    private MeetingType seed(Long owner, String slug, boolean active, boolean secret) {
        TestOwners.ensure(em, owner);
        MeetingType t = new MeetingType();
        t.ownerId = owner;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.active = active;
        t.secret = secret;
        t.persist();
        return t;
    }

    @Test
    @TestTransaction
    void findBySlugIsScopedToOwner() {
        seed(2001L, "intro-call", true, false);
        // same slug, different owner is allowed
        seed(2002L, "intro-call", true, false);

        assertEquals(2001L, MeetingType.findBySlug(2001L, "intro-call").ownerId);
        assertEquals(2002L, MeetingType.findBySlug(2002L, "intro-call").ownerId);
        assertNull(MeetingType.findBySlug(2003L, "intro-call"), "no such owner -> null");
    }

    @Test
    @TestTransaction
    void listPublicAndListForOwnerAreScoped() {
        seed(2001L, "a", true, false);
        // secret -> not public
        seed(2001L, "b", true, true);
        // inactive -> not public
        seed(2001L, "c", false, false);
        // other owner
        seed(2002L, "d", true, false);
        // only "a"
        assertEquals(1, MeetingType.listPublic(2001L).size());
        // a,b,c — includes secret+inactive
        assertEquals(3, MeetingType.listForOwner(2001L).size());
        assertTrue(MeetingType
            .listForOwner(2002L)
            .stream()
            .allMatch(t -> t.ownerId.equals(2002L)));
    }
}
