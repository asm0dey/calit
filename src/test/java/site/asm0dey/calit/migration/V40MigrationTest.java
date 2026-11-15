package site.asm0dey.calit.migration;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/**
 * V40 adds the multi-slot columns; all nullable or defaulted, so the previous release keeps working.
 */
@QuarkusTest
class V40MigrationTest {
    @Inject
    EntityManager em;

    private List<String> column(String table, String name) {
        return ((List<Object[]>) em
            .createNativeQuery(
                    "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = ?1 AND column_name = ?2"
            )
            .setParameter(1, table)
            .setParameter(2, name)
            .getResultList())
            .stream()
            .map(r -> r[0] + " " + r[1] + " " + r[2])
            .toList();
    }

    @Test
    @TestTransaction
    void columnsExistWithDefaults() {
        assertThat(column("meeting_type", "multi_slot_min")).containsExactly("integer YES null");
        assertThat(column("meeting_type", "multi_slot_max")).containsExactly("integer YES null");
        assertThat(column("meeting_type", "multi_slot_same_day")).containsExactly("boolean NO false");
        assertThat(column("booking", "set_id")).containsExactly("uuid YES null");
        assertThat(em
            .createNativeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = 'booking_set_id_idx'")
            .getResultList()
        )
            .singleElement()
            .asString()
            .contains("(set_id)");
    }
}
