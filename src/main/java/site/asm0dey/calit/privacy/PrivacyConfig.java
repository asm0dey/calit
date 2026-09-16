package site.asm0dey.calit.privacy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The two privacy knobs an operator can turn. There is deliberately no global "GDPR mode" switch:
 * the regulation's territorial scope follows the data subject, not the server, so a switch would
 * remove the tools without removing the obligation.
 */
@ApplicationScoped
public class PrivacyConfig {

    final boolean inviteeErasure;

    final Optional<Integer> retentionDays;

    @Inject
    public PrivacyConfig(
            @ConfigProperty(name = "calit.privacy.invitee-erasure", defaultValue = "true") boolean inviteeErasure,
            @ConfigProperty(name = "calit.retention.booking-days") Optional<Integer> retentionDays) {
        this.inviteeErasure = inviteeErasure;
        this.retentionDays = retentionDays;
    }

    /** When false, the manage page shows the operator's contact address instead of the erase button. */
    public boolean inviteeErasureEnabled() {
        return inviteeErasure;
    }

    /** Instance-wide retention window. Empty = keep bookings forever, which is the default. */
    public Optional<Integer> bookingRetentionDays() {
        return retentionDays.filter(d -> d > 0);
    }
}
