package site.asm0dey.calit.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Pure unit test -- no Quarkus. SMTP unreachable must report UP (informational), never DOWN,
// so a down mail server can't pull a replica out of rotation now that the outbox covers delivery.
class SmtpHealthCheckTest {

    @Test
    void unreachableHostReportsUpWithState() {
        SmtpHealthCheck c = new SmtpHealthCheck();
        c.mock = false;
        c.host = Optional.of("localhost");
        c.port = 2; // closed port -> connection refused fast, no slow timeout
        HealthCheckResponse r = c.call();
        assertEquals(HealthCheckResponse.Status.UP, r.getStatus(), "informational: always UP");
        assertTrue(r.getData().orElseThrow().containsKey("state"));
    }

    @Test
    void mockedReportsUp() {
        SmtpHealthCheck c = new SmtpHealthCheck();
        c.mock = true;
        c.host = Optional.empty();
        c.port = 587;
        assertEquals(HealthCheckResponse.Status.UP, c.call().getStatus());
    }
}
