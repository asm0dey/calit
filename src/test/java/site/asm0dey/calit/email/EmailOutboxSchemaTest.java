package site.asm0dey.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Proves the V14 table exists and the entity (Task 2) maps to it under Hibernate validate-only.
@QuarkusTest
class EmailOutboxSchemaTest {
    @Test
    void tableExistsAndMapsCleanly() {
        long n = QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count());
        assertEquals(0L, n);
    }
}
