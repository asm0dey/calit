package site.asm0dey.calit.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals("alice AUDIT actor=evil action=grant-admin target=user:1", result);
        // No CR/LF survives, so the value cannot break out onto its own audit line.
        assertEquals(-1, result.indexOf('\n'));
        assertEquals(-1, result.indexOf('\r'));
    }

    @Test
    void safeMapsNullToDash() {
        assertEquals("-", AuditLog.safe(null));
    }

    @Test
    void safeLeavesCleanValueUnchanged() {
        assertEquals("user:42", AuditLog.safe("user:42"));
    }

    @Test
    void eventNeverThrowsOnHostileOrNullFields() {
        AuditLog log = new AuditLog();
        assertDoesNotThrow(() -> log.event("a\nb", "act\rion", null, "1.2.3.4\n"));
    }
}
