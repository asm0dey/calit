package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class OwnerSettingsForOwnerTest {

    @Inject
    EntityManager em;

    @Test
    @TestTransaction
    void forOwnerReturnsOnlyThatOwnersRow() {
        TestOwners.ensure(em, 1001L);
        TestOwners.ensure(em, 1002L);
        OwnerSettings a = new OwnerSettings();
        a.ownerId = 1001L;
        a.ownerName = "A";
        a.ownerEmail = "a@x.com";
        a.timezone = "UTC";
        a.persist();
        OwnerSettings b = new OwnerSettings();
        b.ownerId = 1002L;
        b.ownerName = "B";
        b.ownerEmail = "b@x.com";
        b.timezone = "Europe/Berlin";
        b.persist();

        assertEquals("A", OwnerSettings.forOwner(1001L).ownerName);
        assertEquals("Europe/Berlin", OwnerSettings.forOwner(1002L).timezone);
        assertNull(OwnerSettings.forOwner(9999L), "unknown owner -> null");
    }

    @Test
    @TestTransaction
    void seedWritesTheNotNullPlaceholders() {
        TestOwners.ensure(em, 4242L);
        var s = OwnerSettings.seed(4242L, "invited@example.com");
        assertEquals(4242L, s.ownerId);
        assertEquals("", s.ownerName);
        assertEquals("invited@example.com", s.ownerEmail);
        assertEquals("UTC", s.timezone);
    }

    @Test
    @TestTransaction
    void seedTreatsANullEmailAsEmptyNotNull() {
        // owner_email is NOT NULL; a path with no address to seed (self-service signup, or Google
        // returning no email) must still satisfy the constraint.
        TestOwners.ensure(em, 4243L);
        assertEquals("", OwnerSettings.seed(4243L, null).ownerEmail);
    }
}
