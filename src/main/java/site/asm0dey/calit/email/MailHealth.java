package site.asm0dey.calit.email;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Map;
import site.asm0dey.calit.health.SmtpHealthCheck;

/**
 * Whether this deployment can actually deliver mail, and what it already failed to deliver.
 * <p>
 * The single seam behind every "email isn't working" warning in the UI: the owner dashboard banner,
 * the copy-link toast, and the guest's confirmation page. Named {@code mailHealth} so Qute fragments
 * with no owning resource can reach it as {@code {cdi:mailHealth.degraded}}.
 * <p>
 * Reachability delegates to {@link SmtpHealthCheck} rather than duplicating the probe -- that check
 * already publishes the three states at {@code /q/health/ready} under {@code data.state}, and having
 * one prober means the banner can never disagree with the health endpoint. Its result is cached for
 * {@link #PROBE_TTL_MS}: the probe opens a TCP socket with a 2s timeout, which must not happen on
 * every page render.
 * <p>
 * ponytail: a plain volatile-field cache, not quarkus-cache -- one value, one TTL, no new extension.
 */
@Named("mailHealth")
@ApplicationScoped
public class MailHealth {

    /** Long enough that page renders never probe; short enough that a fixed SMTP box clears fast. */
    static final long PROBE_TTL_MS = 60_000L;

    /** Values published by {@link SmtpHealthCheck} under {@code data.state}. Pinned by a test. */
    static final String STATE_REACHABLE = "reachable";

    static final String STATE_UNREACHABLE = "unreachable";

    /** How mail delivery is doing. {@code UNCONFIGURED} and {@code UNREACHABLE} are different
     *  operator problems: nobody set SMTP up, versus SMTP is set up and the host won't answer. */
    public enum State {
        OK,
        UNCONFIGURED,
        UNREACHABLE
    }

    /**
     * A snapshot for one render. {@code deadLetters} counts {@code email_outbox} rows we gave up on
     * ({@code next_attempt_at IS NULL}, never sent) -- the signal the reachability probe misses,
     * because a host that accepts TCP but rejects auth probes as reachable while mail never leaves.
     */
    public record Status(State state, long deadLetters) {
        public boolean degraded() {
            return state != State.OK;
        }

        public boolean unconfigured() {
            return state == State.UNCONFIGURED;
        }

        public boolean unreachable() {
            return state == State.UNREACHABLE;
        }

        public boolean hasDeadLetters() {
            return deadLetters > 0;
        }
    }

    final SmtpHealthCheck smtp;

    // @Any: SmtpHealthCheck carries the MicroProfile @Readiness qualifier, which suppresses CDI's
    // implicit @Default -- a plain @Inject here (implying @Default) is unsatisfied. @Any matches any
    // qualifier and the concrete type still resolves to exactly one bean.
    @Inject
    public MailHealth(@Any SmtpHealthCheck smtp) {
        this.smtp = smtp;
    }

    private volatile State cachedState;

    private volatile long probedAtMs;

    /** Cached reachability + a live dead-letter count. Safe to call from a page render. */
    public Status status() {
        return new Status(state(), deadLetters());
    }

    /** Convenience for {@code {cdi:mailHealth.degraded}} in fragments that take no parameters. */
    public boolean degraded() {
        return state() != State.OK;
    }

    /**
     * True when this deployment still owes mail to {@code recipient}: a parked row that has not been
     * sent, whether it is still retrying or was given up on.
     * <p>
     * ponytail: keyed by address, not booking -- {@code email_outbox} has no booking column and
     * adding one means a migration plus threading an id through the generic MailSender seam. The
     * cost is that a guest who books twice during an outage sees the warning on both, which reads as
     * "we have undelivered mail for you" and is true. Add the column only if per-booking precision
     * is ever actually needed.
     */
    public boolean undeliveredFor(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return false;
        }
        return EmailOutbox.count("recipient = ?1 and sentAt is null", recipient) > 0;
    }

    /** Rows we gave up on: dead ({@code nextAttemptAt} null) and never delivered. */
    long deadLetters() {
        return EmailOutbox.count("nextAttemptAt is null and sentAt is null");
    }

    private State state() {
        var now = System.currentTimeMillis();
        var cached = cachedState;
        if (cached != null && now - probedAtMs < PROBE_TTL_MS) {
            return cached;
        }
        var fresh = probe();
        cachedState = fresh;
        probedAtMs = now;
        return fresh;
    }

    private State probe() {
        Map<String, Object> data = smtp.call().getData().orElse(Map.of());
        var reported = String.valueOf(data.get("state"));
        if (STATE_REACHABLE.equals(reported)) {
            return State.OK;
        }
        if (STATE_UNREACHABLE.equals(reported)) {
            return State.UNREACHABLE;
        }
        // "mocked-or-unconfigured", or a value we don't recognise: treat as "can't promise delivery".
        return State.UNCONFIGURED;
    }
}
