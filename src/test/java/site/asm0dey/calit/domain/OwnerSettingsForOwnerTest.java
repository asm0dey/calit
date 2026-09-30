package site.asm0dey.calit.domain;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(OwnerSettings.forOwner(1001L).ownerName).isEqualTo("A");
        assertThat(OwnerSettings.forOwner(1002L).timezone).isEqualTo("Europe/Berlin");
        assertThat(OwnerSettings.forOwner(9999L)).as("unknown owner -> null").isNull();
    }

    /**
     * {@code timezone} is NOT NULL and eleven call sites do an unguarded {@code
     * ZoneId.of(settings.timezone)} -- including the owner's PUBLIC booking page and the booking
     * transaction -- so a value the JDK cannot parse 500s them all (calit-4whp).
     */
    @Test
    void coerceZoneKeepsAKnownZoneAndReplacesEverythingElse() {
        assertThat(OwnerSettings.coerceZone("Europe/Amsterdam")).isEqualTo("Europe/Amsterdam");
        assertThat(OwnerSettings.coerceZone("UTC")).isEqualTo("UTC");
        assertThat(OwnerSettings.coerceZone("Not/AZone")).isEqualTo("UTC");
        assertThat(OwnerSettings.coerceZone(null)).isEqualTo("UTC");
        assertThat(OwnerSettings.coerceZone("")).isEqualTo("UTC");
        assertThat(OwnerSettings.coerceZone("   ")).isEqualTo("UTC");
    }

    /**
     * The picker is fed from the same list the guard checks against, so every option survives.
     */
    @Test
    void coerceZoneAcceptsEveryZoneThePickerCanOffer() {
        for (String z : OwnerSettings.zoneIds()) {
            assertThat(OwnerSettings.coerceZone(z)).isEqualTo(z);
        }
    }

    @Test
    @TestTransaction
    void seedWritesTheNotNullPlaceholders() {
        TestOwners.ensure(em, 4242L);
        var s = OwnerSettings.seed(4242L, "invited@example.com");
        assertThat(s.ownerId).isEqualTo(4242L);
        assertThat(s.ownerName).isEmpty();
        assertThat(s.ownerEmail).isEqualTo("invited@example.com");
        assertThat(s.timezone).isEqualTo("UTC");
    }

    @Test
    @TestTransaction
    void seedTreatsANullEmailAsEmptyNotNull() {
        // owner_email is NOT NULL; a path with no address to seed (self-service signup, or Google
        // returning no email) must still satisfy the constraint.
        TestOwners.ensure(em, 4243L);
        assertThat(OwnerSettings.seed(4243L, null).ownerEmail).isEmpty();
    }
}
