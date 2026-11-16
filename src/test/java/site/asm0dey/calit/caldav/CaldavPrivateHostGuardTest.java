package site.asm0dey.calit.caldav;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@link RadicaleResource} turns private hosts on for the CalDAV classes that share its app. This
 * pins that the flag never reaches the default app, where the SSRF guard must stay on.
 */
@QuarkusTest
class CaldavPrivateHostGuardTest {
    @Inject
    CaldavConfig config;

    @Test
    void defaultAppRefusesPrivateHosts() {
        assertThat(config.allowPrivateHosts()).isFalse();
    }
}
