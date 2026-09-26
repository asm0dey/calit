package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// Proves the V14 table exists and the entity (Task 2) maps to it under Hibernate validate-only.
@QuarkusTest
class EmailOutboxSchemaTest {
    // Lambda, not EmailOutbox::count: a method reference binds to PanacheEntityBase.count, which Panache
    // does not rewrite, and throws "did you forget to annotate your entity with @Entity?".
    @SuppressWarnings("java:S1612")
    @Test
    void tableExistsAndMapsCleanly() {
        long n = QuarkusTransaction
            .requiringNew()
            .call(() -> EmailOutbox.count());
        assertEquals(0L, n);
    }
}
