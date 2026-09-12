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
        // A targeted single-column UPDATE, not findById -> mutate -> persist: the row carries no
        // version column, so a read-modify-write would let two concurrent deliveries for the same
        // channel write back each other's stale column and silently drop a timestamp. 0 rows means
        // the channel was deleted between dispatch and delivery; nothing to record.
        int updated = ok
                ? NotificationChannel.update("lastSuccessAt = ?1 where id = ?2", at, channelId)
                : NotificationChannel.update("lastFailureAt = ?1 where id = ?2", at, channelId);
        if (updated == 0) {
            return;
        }
        Log.debugf("channel %d delivery %s", channelId, ok ? "ok" : "failed");
    }
}
