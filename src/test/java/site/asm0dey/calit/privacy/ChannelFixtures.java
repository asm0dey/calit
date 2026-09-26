package site.asm0dey.calit.privacy;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Instant;
import site.asm0dey.calit.notify.NotificationChannel;

/**
 * Shared test fixture: one outbound notification channel for an owner, seeded in its own
 * transaction (mirrors {@link ErasureFixtures}) so callers can invoke it directly from a plain
 * {@code @Test} method without wrapping it themselves.
 */
public final class ChannelFixtures {
    private ChannelFixtures() {
    }

    /**
     * Persists a channel for {@code ownerId} with the given (plaintext) URL. Returns its id.
     */
    public static Long seedChannel(Long ownerId, String url) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = url;
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }
}
