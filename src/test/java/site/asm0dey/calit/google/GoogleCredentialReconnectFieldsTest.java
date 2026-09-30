package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
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
        assertThat(reloaded.reconnectNotifiedAt).isEqualTo(t);
        assertThat(reloaded.lastProbedAt).isEqualTo(t);

        GoogleCredential fresh = new GoogleCredential();
        assertThat(fresh.reconnectNotifiedAt).isNull();
        assertThat(fresh.lastProbedAt).isNull();
    }
}
