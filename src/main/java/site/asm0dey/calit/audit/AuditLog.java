package site.asm0dey.calit.audit;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Structured audit events for security-relevant actions (SEC-SECRET-05): privileged mutations and
 * auth outcomes. Never logs secrets — only actor / action / target / source IP. Emitted under a
 * dedicated "audit" logger category so log aggregation can route/retain these separately.
 */
@ApplicationScoped
public class AuditLog {

    private static final Logger LOG = Logger.getLogger("audit");

    public void event(String actor, String action, String target, String sourceIp) {
        LOG.infof("AUDIT actor=%s action=%s target=%s ip=%s",
                safe(actor), safe(action), safe(target), safe(sourceIp));
    }

    /** Strip CR/LF so a hostile field value cannot forge a fake audit line (log injection). */
    static String safe(String s) {
        if (s == null) {
            return "-";
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
