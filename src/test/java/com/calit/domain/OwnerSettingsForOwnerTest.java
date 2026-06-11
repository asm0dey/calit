package com.calit.domain;

import com.calit.user.TestOwners;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        a.ownerId = 1001L; a.ownerName = "A"; a.ownerEmail = "a@x.com"; a.timezone = "UTC";
        a.persist();
        OwnerSettings b = new OwnerSettings();
        b.ownerId = 1002L; b.ownerName = "B"; b.ownerEmail = "b@x.com"; b.timezone = "Europe/Berlin";
        b.persist();

        assertEquals("A", OwnerSettings.forOwner(1001L).ownerName);
        assertEquals("Europe/Berlin", OwnerSettings.forOwner(1002L).timezone);
        assertNull(OwnerSettings.forOwner(9999L), "unknown owner -> null");
    }
}
