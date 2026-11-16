package site.asm0dey.calit.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The grace-window tests build their schedulers by hand, so this proves the key still binds.
 */
@QuarkusComponentTest
@TestConfigProperty(key = "calit.scheduler.grace-seconds", value = "120")
class GraceSecondsBindingTest {
    @Inject
    PendingExpiryScheduler pendingExpiry;
    @Inject
    ReminderScheduler reminders;

    @Test
    void graceSecondsBindsInBothSchedulers() {
        assertThat(ClientProxy.unwrap(pendingExpiry).graceSeconds).isEqualTo(120);
        assertThat(ClientProxy.unwrap(reminders).graceSeconds).isEqualTo(120);
    }
}
