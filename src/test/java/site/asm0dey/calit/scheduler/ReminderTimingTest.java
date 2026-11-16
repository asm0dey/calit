package site.asm0dey.calit.scheduler;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReminderTimingTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void sendAtIsStartMinusLead() {
        assertThat(ReminderScheduler.reminderSendAt(Instant.parse("2026-01-02T10:00:00Z"), Duration.ofHours(24), NOW))
            .contains(Instant.parse("2026-01-01T10:00:00Z"));
    }

    @Test
    void sendAtInThePastIsSkipped() {
        assertThat(ReminderScheduler.reminderSendAt(Instant.parse("2026-01-01T01:00:00Z"), Duration.ofHours(24), NOW))
            .isEmpty();
    }

    @Test
    void sendAtExactlyNowIsSkipped() {
        assertThat(ReminderScheduler.reminderSendAt(Instant.parse("2026-01-02T00:00:00Z"), Duration.ofHours(24), NOW))
            .isEmpty();
    }
}
