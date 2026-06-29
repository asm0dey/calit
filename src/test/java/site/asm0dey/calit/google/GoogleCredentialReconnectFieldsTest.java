package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GoogleCredentialReconnectFieldsTest {

    @Test
    @TestTransaction
    void persistsAndReadsReconnectTrackingFields() {
        var t = Instant.parse("2026-06-15T10:00:00Z");
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-fields";
        c.reconnectNotifiedAt = t;
        c.lastProbedAt = t;
        c.persist();
        c.flush();

        GoogleCredential reloaded = GoogleCredential.findById(c.id);
        assertEquals(t, reloaded.reconnectNotifiedAt);
        assertEquals(t, reloaded.lastProbedAt);

        GoogleCredential fresh = new GoogleCredential();
        assertNull(fresh.reconnectNotifiedAt);
        assertNull(fresh.lastProbedAt);
    }
}
