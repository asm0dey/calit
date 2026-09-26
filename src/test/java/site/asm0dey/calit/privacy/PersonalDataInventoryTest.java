package site.asm0dey.calit.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The compliance property. Every table and every column in the live schema must appear in
 * {@link PersonalData}, classified as personal or not. A migration that adds a column fails this
 * test until someone decides what erasure does with it — which is exactly the judgement a
 * reflection-over-JPA-metadata approach would have hidden.
 */
@QuarkusTest
class PersonalDataInventoryTest {
    /**
     * Flyway's own bookkeeping table is infrastructure, not application data.
     */
    private static final Set<String> IGNORED = Set.of("flyway_schema_history");
    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    private List<String> strings(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    private Set<String> liveTables() {
        return new TreeSet<>(strings(
                "select table_name from information_schema.tables " + "where table_schema='public' and table_type='BASE TABLE'"
        )
            .stream()
            .filter(t -> !IGNORED.contains(t))
            .collect(Collectors.toSet())
        );
    }

    private Set<String> liveColumns(String table) {
        return new TreeSet<>(
                strings(
                        "select column_name from information_schema.columns " + "where table_schema='public' and table_name='" + table + "'"
                )
        );
    }

    @Test
    @Transactional
    void everyLiveTableIsClassified() {
        var classified = new TreeSet<>(PersonalData.TABLES.stream().map(PersonalData.Classified::table).toList());
        assertEquals(
                liveTables(),
                classified,
                "PersonalData.TABLES must name exactly the live application tables — "
                + "add the new table to the inventory and say what erasure does with it"
        );
    }

    @Test
    @Transactional
    void everyLiveColumnIsClassified() {
        for (PersonalData.Classified c : PersonalData.TABLES) {
            assertEquals(
                    liveColumns(c.table()),
                    new TreeSet<>(c.columns()),
                    "PersonalData column list for '" + c.table() + "' is out of date — " + "classify the new column as personal or not"
            );
        }
    }

    @Test
    @Transactional
    void personalColumnsAreASubsetOfKnownColumns() {
        for (PersonalData.Classified c : PersonalData.TABLES) {
            assertTrue(
                    c.columns().containsAll(c.personalColumns()),
                    "personal columns of '" + c.table() + "' must all be real columns"
            );
        }
    }

    @Test
    void everyPersonalTableDeclaresANonTrivialEraseRoute() {
        for (PersonalData.Classified c : PersonalData.TABLES) {
            if (c.personalColumns().isEmpty()) {
                continue;
            }
            assertNotSame(
                    PersonalData.EraseRoute.NOT_PERSONAL,
                    c.route(),
                    "'" + c.table() + "' carries personal columns but declares no erase route"
            );
            assertNotSame(
                    PersonalData.Subject.NONE,
                    c.subject(),
                    "'" + c.table() + "' carries personal columns but names no data subject"
            );
        }
    }

    @Test
    void outboundDestinationsAreRecorded() {
        assertTrue(PersonalData.OUTBOUND.size() >= 4, "the four known outbound destinations must be listed");
        assertTrue(
                PersonalData.OUTBOUND
                    .stream()
                    .anyMatch(d -> !d.reachableByErasure()),
                "at least one destination is known to be beyond erasure — say so"
        );
    }
}
