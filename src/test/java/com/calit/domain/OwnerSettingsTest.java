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
        OwnerSettings s = new OwnerSettings();
        s.id = OwnerSettings.SINGLETON_ID;
        s.ownerName = "Pavel";
        s.ownerEmail = "p@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        OwnerSettings loaded = OwnerSettings.get();
        assertNotNull(loaded);
        assertEquals("Europe/Amsterdam", loaded.timezone);
    }
}
