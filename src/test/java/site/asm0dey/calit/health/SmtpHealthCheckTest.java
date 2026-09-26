package site.asm0dey.calit.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Optional;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

// Pure unit test -- no Quarkus. SMTP unreachable must report UP (informational), never DOWN,
// so a down mail server can't pull a replica out of rotation now that the outbox covers delivery.
class SmtpHealthCheckTest {
    @Test
    void unreachableHostReportsUpWithState() {
        // closed port -> connection refused fast, no slow timeout
        SmtpHealthCheck c = new SmtpHealthCheck(false, Optional.of("localhost"), 2);
        HealthCheckResponse r = c.call();
        assertEquals(HealthCheckResponse.Status.UP, r.getStatus(), "informational: always UP");
        assertTrue(r.getData().orElseThrow().containsKey("state"));
    }

    @Test
    void mockedReportsUp() {
        SmtpHealthCheck c = new SmtpHealthCheck(true, Optional.empty(), 587);
        assertEquals(HealthCheckResponse.Status.UP, c.call().getStatus());
    }
}
