package site.asm0dey.calit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

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
        assertThat(loaded).isNotNull();
        // DB default TRUE
        assertThat(loaded.ownerNotificationsEnabled).isTrue();

        loaded.ownerNotificationsEnabled = false;
        loaded.persist();
        assertThat(OwnerSettings.forOwner(1L).ownerNotificationsEnabled).isFalse();
    }
}
