package site.asm0dey.calit.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

/**
 * The home-redirect opt-out defaults ON. {@code DatabaseResetCallback} truncates {@code
 * owner_settings} before each test and reseeds only the baseline {@code admin} {@code AppUser}
 * (id 1) — it never seeds an {@code OwnerSettings} row (see {@code EmailMissingOwnerSettingsTest}
 * for a test that deliberately exercises that missing-row state). This test seeds its own row via
 * {@link OwnerSettings#seed} so it has one to assert against.
 */
@QuarkusTest
class OwnerSettingsHomeRedirectTest {
    @Test
    @Transactional
    void seededOwnerHasHomeRedirectEnabled() {
        OwnerSettings.seed(1L, "admin@example.com");

        assertTrue(OwnerSettings.forOwner(1L).homeRedirectEnabled);
    }
}
