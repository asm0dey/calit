package site.asm0dey.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OwnerSettingsExtensionsTest {

    @Test
    @TestTransaction
    void ownerNotificationsDefaultsTrueAndToggles() {
        // Upsert the singleton (the RestAssured tests may already have committed id=1).
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        OwnerSettings loaded = OwnerSettings.forOwner(1L);
        assertNotNull(loaded);
        assertTrue(loaded.ownerNotificationsEnabled); // DB default TRUE

        loaded.ownerNotificationsEnabled = false;
        loaded.persist();
        assertFalse(OwnerSettings.forOwner(1L).ownerNotificationsEnabled);
    }
}
