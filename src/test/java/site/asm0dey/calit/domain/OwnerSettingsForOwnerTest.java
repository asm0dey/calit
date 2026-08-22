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

    /**
     * {@code timezone} is NOT NULL and eleven call sites do an unguarded {@code
     * ZoneId.of(settings.timezone)} -- including the owner's PUBLIC booking page and the booking
     * transaction -- so a value the JDK cannot parse 500s them all (calit-4whp).
     */
    @Test
    void coerceZoneKeepsAKnownZoneAndReplacesEverythingElse() {
        assertEquals("Europe/Amsterdam", OwnerSettings.coerceZone("Europe/Amsterdam"));
        assertEquals("UTC", OwnerSettings.coerceZone("UTC"));
        assertEquals("UTC", OwnerSettings.coerceZone("Not/AZone"));
        assertEquals("UTC", OwnerSettings.coerceZone(null));
        assertEquals("UTC", OwnerSettings.coerceZone(""));
        assertEquals("UTC", OwnerSettings.coerceZone("   "));
    }

    /** The picker is fed from the same list the guard checks against, so every option survives. */
    @Test
    void coerceZoneAcceptsEveryZoneThePickerCanOffer() {
        for (String z : OwnerSettings.zoneIds()) {
            assertEquals(z, OwnerSettings.coerceZone(z));
        }
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
