package com.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class OwnerSettingsTest {
    @Test
    @TestTransaction
    void persistsAndReadsSingleton() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Pavel";
        s.ownerEmail = "p@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        OwnerSettings loaded = OwnerSettings.forOwner(1L);
        assertNotNull(loaded);
        assertEquals("Europe/Amsterdam", loaded.timezone);
    }
}
