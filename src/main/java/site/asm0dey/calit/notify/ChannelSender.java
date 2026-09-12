package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.alexmond.notify4j.Notifications;
import org.alexmond.notify4j.SendResult;

/**
 * The async half of the delivery path: one blocking HTTP POST and one timestamp write, nothing
 * else. {@code sendOnce} is stateless — no per-owner instance cache, no {@code AutoCloseable}
 * lifecycle — and gives per-row failure attribution a shared {@code Notifications} instance cannot.
 * notify4j's transition filtering and reminder machinery stay off: both hold in-memory per-replica
 * state, which cannot work coherently across calit's N stateless replicas.
 *
 * <p>The booking is already committed and emailed before any of this runs, so nothing here may
 * escape.
 */
@ApplicationScoped
public class ChannelSender {

    final NotifyConfig config;

    final ChannelPolicy policy;

    final ChannelStamp stamp;

    @Inject
    public ChannelSender(NotifyConfig config, ChannelPolicy policy, ChannelStamp stamp) {
        this.config = config;
        this.policy = policy;
        this.stamp = stamp;
    }

    void onDelivery(@ObservesAsync ChannelDelivery d) {
        var at = Instant.now();
        boolean ok;
        try {
            // Re-check at SEND time: a row saved before the allowlist was tightened stops
            // delivering rather than being grandfathered.
            if (!policy.check(d.url()).ok()) {
                Log.warnf("channel %d blocked by policy at send time", d.channelId());
                ok = false;
            } else {
                SendResult r = Notifications.sendOnce(List.of(d.url()), d.message(), config.http());
                ok = !r.anyFailed();
            }
        } catch (RuntimeException e) {
            // Never log d.url() — it is secret-bearing. The channel id is enough to find the row.
            Log.warnf(e, "channel %d delivery threw", d.channelId());
            ok = false;
        }
        try {
            stamp.stamp(d.channelId(), ok, at);
        } catch (RuntimeException e) {
            Log.warnf(e, "could not stamp channel %d", d.channelId());
        }
    }
}
