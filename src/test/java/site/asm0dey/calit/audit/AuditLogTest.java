package site.asm0dey.calit.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the audit emitter's log-injection guard. {@code safe()} is package-private so the
 * CR/LF stripping (which prevents a hostile field value from forging a fake audit line) can be
 * asserted directly without scraping the JBoss log.
 */
class AuditLogTest {
    @Test
    void safeStripsCarriageReturnAndNewline() {
        // A forged second line "AUDIT actor=evil..." injected via a field must be flattened to spaces.
        var hostile = "alice\nAUDIT actor=evil action=grant-admin\rtarget=user:1";
        String result = AuditLog.safe(hostile);
        assertThat(result).isEqualTo("alice AUDIT actor=evil action=grant-admin target=user:1");
        // No CR/LF survives, so the value cannot break out onto its own audit line.
        assertThat(result.indexOf('\n')).isEqualTo(-1);
        assertThat(result.indexOf('\r')).isEqualTo(-1);
    }

    @Test
    void safeMapsNullToDash() {
        assertThat(AuditLog.safe(null)).isEqualTo("-");
    }

    @Test
    void safeLeavesCleanValueUnchanged() {
        assertThat(AuditLog.safe("user:42")).isEqualTo("user:42");
    }

    @Test
    void eventNeverThrowsOnHostileOrNullFields() {
        AuditLog log = new AuditLog();
        assertThatCode(() -> log.event("a\nb", "act\rion", null, "1.2.3.4\n")).doesNotThrowAnyException();
    }
}
