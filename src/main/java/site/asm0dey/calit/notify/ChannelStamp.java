package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The one DB write on the async side. A SEPARATE bean on purpose: calling a {@code @Transactional}
 * method on {@code this} from {@link ChannelSender} would bypass the interceptor entirely and the
 * write would silently never commit. {@code @ActivateRequestContext} covers the case where the
 * container gives an {@code @ObservesAsync} observer no request context (it is a no-op when one is
 * already active).
 */
@ApplicationScoped
public class ChannelStamp {

    @Transactional
    @ActivateRequestContext
    public void stamp(Long channelId, boolean ok, Instant at) {
        NotificationChannel c = NotificationChannel.findById(channelId);
        if (c == null) {
            return; // deleted between dispatch and delivery; nothing to record
        }
        if (ok) {
            c.lastSuccessAt = at;
        } else {
            c.lastFailureAt = at;
        }
        c.persist();
        Log.debugf("channel %d delivery %s", channelId, ok ? "ok" : "failed");
    }
}
