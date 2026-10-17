package site.asm0dey.calit.scheduler;

import module java.base;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.caldav.CaldavCalendarPort;
import site.asm0dey.calit.caldav.CaldavClient;
import site.asm0dey.calit.caldav.CaldavException;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarAccount;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.google.GoogleTokenService;
import site.asm0dey.calit.i18n.AppLocales;

/**
 * Feature: Google and CalDAV disconnect detection. Runs on EVERY replica every probe-interval. Multi-node-safe
 * with NO leader: each phase claims rows with SELECT ... FOR UPDATE SKIP LOCKED, so a concurrent
 * replica's identical query skips rows this one already locked.
 */
@ApplicationScoped
public class GoogleConnectionScheduler {
    final Duration probeInterval;
    final EntityManager em;
    final GoogleTokenService tokens;
    final EmailService emailService;
    final CaldavClient caldav;

    @Inject
    public GoogleConnectionScheduler(
            EntityManager em,
            GoogleTokenService tokens,
            EmailService emailService,
            CaldavClient caldav,
            @ConfigProperty(name = "calit.google.probe-interval") Duration probeInterval
    ) {
        this.em = em;
        this.tokens = tokens;
        this.emailService = emailService;
        this.caldav = caldav;
        this.probeInterval = probeInterval;
    }

    @Scheduled(every = "{calit.google.probe-interval}")
    void tick() {
        probeDueCredentials();
        notifyPendingDisconnects();
    }

    /**
     * Claims credentials not probed within half the interval (stamping last_probed_at so other
     * replicas skip them), then forces a refresh per credential in its own transaction. The
     * half-interval grace stops staggered replica timers from re-probing the same account each tick.
     * ponytail: last_probed_at gating is an optimization (avoids redundant Google calls); the
     * notify gate below is what guarantees correctness.
     */
    public void probeDueCredentials() {
        List<Long> ids = claimDueForProbe();
        var now = Instant.now();
        for (Long id : ids) {
            CalendarProvider provider = providerOf(id);
            if (provider == null) {
                // disconnected since the claim
                continue;
            }
            if (provider == CalendarProvider.CALDAV) {
                probeCaldav(id);
            } else {
                // own @Transactional; sets/clears needs_reconnect
                tokens.probe(id, now);
            }
        }
    }

    private CalendarProvider providerOf(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> CalendarAccount.findById(id) instanceof CalendarAccount a ? a.provider() : null);
    }

    /**
     * Only UNAUTHORIZED flags the account: like the Google probe's TRANSIENT, a server blip (unreachable,
     * 5xx) leaves state untouched and is retried next cycle, since it says nothing about the password.
     */
    private void probeCaldav(Long id) {
        CaldavAccount a = QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.findById(id));
        if (a == null) {
            return;
        }
        try {
            caldav.probe(a);
        } catch (CaldavException ex) {
            CaldavCalendarPort.flagIfUnauthorized(a, ex);
            return;
        }
        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                CaldavAccount fresh = CaldavAccount.findById(id);
                if (fresh != null) {
                    fresh.needsReconnect = false;
                    // INVARIANT: clearing needsReconnect resets the notify gate
                    fresh.reconnectNotifiedAt = null;
                }
            });
    }

    List<Long> claimDueForProbe() {
        var graceSeconds = Math.max(1, probeInterval.toSeconds() / 2);
        return QuarkusTransaction
            .requiringNew()
            .call(() -> {
                @SuppressWarnings("unchecked")
                List<Number> ids = em
                    .createNativeQuery(
                            "SELECT id FROM calendar_account WHERE provider IN ('GOOGLE', 'CALDAV') "
                            + "AND (last_probed_at IS NULL "
                            + "   OR last_probed_at <= now() - make_interval(secs => :secs)) "
                            + "ORDER BY last_probed_at NULLS FIRST "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50"
                    )
                    .setParameter("secs", (double) graceSeconds)
                    .getResultList();
                List<Long> claimed = new ArrayList<>();
                var now = Instant.now();
                for (Number n : ids) {
                    Long id = n.longValue();
                    CalendarAccount c = CalendarAccount.findById(id);
                    // stamped in the lock-holding transaction
                    c.lastProbedAt = now;
                    claimed.add(id);
                }
                return claimed;
            });
    }

    /**
     * Claims disconnected-and-not-yet-notified credentials, stamps reconnect_notified_at in the
     * lock-holding transaction (exactly-once email gate), then emails each owner. MailSender.send
     * never throws (outbox fallback), so a stamped mail is always eventually delivered.
     */
    public void notifyPendingDisconnects() {
        List<Pending> pending = claimUnnotifiedDisconnects();
        for (Pending p : pending) {
            emailService.sendCalendarDisconnected(
                    p.ownerId(),
                    p.ownerEmail(),
                    p.accountLabel(),
                    p.provider(),
                    p.locale()
            );
        }
    }

    private record Pending(
            Long ownerId,
            String ownerEmail,
            String accountLabel,
            CalendarProvider provider,
            Locale locale
    ) {}

    List<Pending> claimUnnotifiedDisconnects() {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> {
                @SuppressWarnings("unchecked")
                List<Number> ids = em
                    .createNativeQuery(
                            "SELECT id FROM calendar_account WHERE provider IN ('GOOGLE', 'CALDAV') "
                            + "AND needs_reconnect = true AND reconnect_notified_at IS NULL "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50"
                    )
                    .getResultList();
                List<Pending> out = new ArrayList<>();
                var now = Instant.now();
                for (Number n : ids) {
                    CalendarAccount c = CalendarAccount.findById(n.longValue());
                    // claim: prevents any replica re-sending
                    c.reconnectNotifiedAt = now;
                    OwnerSettings s = OwnerSettings.forOwner(c.ownerId);
                    if (s != null) {
                        out.add(
                                new Pending(c.ownerId, s.ownerEmail, c.label(), c.provider(), AppLocales.pick(s.locale))
                        );
                    }
                }
                return out;
            });
    }
}
