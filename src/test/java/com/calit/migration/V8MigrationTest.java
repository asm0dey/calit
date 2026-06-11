package com.calit.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V8 added owner_id to the 6 root tables plus availability_rule and date_override
 * (which carry global meeting_type_id IS NULL rows), and dropped the global booking_field seed.
 */
@QuarkusTest
class V8MigrationTest {

    @Inject
    EntityManager em;

    private boolean hasOwnerIdColumn(String table) {
        Long count = ((Number) em.createNativeQuery(
                "select count(*) from information_schema.columns " +
                "where table_name = :t and column_name = 'owner_id'")
                .setParameter("t", table)
                .getSingleResult()).longValue();
        return count == 1L;
    }

    @Test
    @Transactional
    void ownerIdAddedToAllSixRootTables() {
        for (String t : new String[]{"owner_settings", "meeting_type", "booking",
                "google_credential", "google_calendar", "booking_field"}) {
            assertTrue(hasOwnerIdColumn(t), "owner_id missing on " + t);
        }
    }

    @Test
    @Transactional
    void ownerIdAddedToAvailabilityRuleAndDateOverride() {
        // Global rows (meeting_type_id IS NULL) need their own owner attribution.
        assertTrue(hasOwnerIdColumn("availability_rule"), "owner_id missing on availability_rule");
        assertTrue(hasOwnerIdColumn("date_override"), "owner_id missing on date_override");
        // date_override_window stays parent-scoped — it must NOT gain an owner_id.
        assertTrue(!hasOwnerIdColumn("date_override_window"),
                "date_override_window must stay parent-scoped (no owner_id)");
    }

    @Test
    @Transactional
    void globalBookingFieldSeedRowDeleted() {
        Long count = ((Number) em.createNativeQuery(
                "select count(*) from booking_field where meeting_type_id is null and owner_id is null")
                .getSingleResult()).longValue();
        assertEquals(0L, count, "the V1 global description field must be gone");
    }

    @Test
    @Transactional
    void slugUniqueIsPerOwnerNotGlobal() {
        // Global single-column unique on slug must be gone; the composite (owner_id, slug) takes its place.
        Long globalUnique = ((Number) em.createNativeQuery(
                "select count(*) from information_schema.table_constraints tc " +
                "join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name " +
                "where tc.table_name = 'meeting_type' and tc.constraint_type = 'UNIQUE' " +
                "and kcu.column_name = 'slug' " +
                "and (select count(*) from information_schema.key_column_usage k2 " +
                "     where k2.constraint_name = tc.constraint_name) = 1")
                .getSingleResult()).longValue();
        assertEquals(0L, globalUnique, "single-column slug UNIQUE must be replaced by (owner_id, slug)");
    }
}
