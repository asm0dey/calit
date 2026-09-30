package site.asm0dey.calit.health;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(r.getStatus()).as("informational: always UP").isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(r.getData().orElseThrow()).containsKey("state");
    }

    @Test
    void mockedReportsUp() {
        SmtpHealthCheck c = new SmtpHealthCheck(true, Optional.empty(), 587);
        assertThat(c.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }
}
