package site.asm0dey.calit.domain;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class OwnerSettingsEmailLookupTest {
    @Inject
    EntityManager em;

    private void settings(long ownerId, String email) {
        TestOwners.ensure(em, ownerId);
        OwnerSettings s = new OwnerSettings();
        s.ownerId = ownerId;
        s.ownerName = "n";
        s.ownerEmail = email;
        s.timezone = "UTC";
        s.persist();
    }

    @Test
    @TestTransaction
    void findsOwnerIdsCaseInsensitivelyByEmail() {
        settings(1001L, "match@example.com");
        settings(1002L, "other@example.com");

        List<Long> ids = OwnerSettings.findOwnerIdsByEmail("MATCH@example.com");
        assertThat(ids).as("email match is case-insensitive and exact otherwise").isEqualTo(List.of(1001L));
    }

    @Test
    @TestTransaction
    void blankOrNullEmailMatchesNothing() {
        settings(1003L, "x@example.com");
        assertThat(OwnerSettings.findOwnerIdsByEmail(null)).isEmpty();
        assertThat(OwnerSettings.findOwnerIdsByEmail("  ")).isEmpty();
    }
}
