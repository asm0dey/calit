package site.asm0dey.calit.privacy;

import module java.base;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The two privacy knobs an operator can turn. There is deliberately no global "GDPR mode" switch:
 * the regulation's territorial scope follows the data subject, not the server, so a switch would
 * remove the tools without removing the obligation.
 */
@ApplicationScoped
public class PrivacyConfig {
    /**
     * Retention windows longer than this are clamped — here, in {@code RetentionScheduler}'s SQL,
     * and in {@code AdminResource.parseRetentionDays}. An unclamped huge value (however it got into
     * the column — the settings form, or a row written before this cap existed) makes {@code now()
     * - make_interval(days => ...)} raise "timestamp out of range" in Postgres; since one sweep tick
     * is one transaction, that single poison window would stop retention for EVERY owner every day.
     * ~100 years is effectively unbounded for any real deployment.
     */
    public static final int MAX_RETENTION_DAYS = 36500;
    final boolean inviteeErasure;
    final Optional<Integer> retentionDays;

    @Inject
    public PrivacyConfig(
            @ConfigProperty(name = "calit.privacy.invitee-erasure", defaultValue = "true") boolean inviteeErasure,
            @ConfigProperty(name = "calit.retention.booking-days") Optional<Integer> retentionDays
    ) {
        this.inviteeErasure = inviteeErasure;
        this.retentionDays = retentionDays;
    }

    /**
     * When false, the manage page shows the operator's contact address instead of the erase button.
     */
    public boolean inviteeErasureEnabled() {
        return inviteeErasure;
    }

    /**
     * Instance-wide retention window. Empty = keep bookings forever, which is the default. Clamped
     * to {@link #MAX_RETENTION_DAYS}.
     */
    public Optional<Integer> bookingRetentionDays() {
        return retentionDays
            .filter(d -> d > 0)
            .map(d -> Math.min(d, MAX_RETENTION_DAYS));
    }
}
