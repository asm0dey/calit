package site.asm0dey.calit.notify;

import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.NotificationOptions;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The pool {@link ChannelDelivery} events are notified on. A DEDICATED, BOUNDED pool on purpose:
 * with no executor the CDI runtime dispatches {@code fireAsync} on ArC's default executor, which in
 * Quarkus IS the blocking worker pool that also serves every JAX-RS request. One host's dead
 * channel would then park a request-serving thread for the whole retry ladder
 * ({@code Notifications.sendOnce} forces BLOCKING retry -- notify4j coerces {@code
 * nonBlockingRetry} to false -- so at the default 3 attempts that is ~33s: 3x10s read timeout plus
 * 1s+2s backoff), and enough dead channels across enough tenants would starve HTTP serving for
 * everyone. Setting {@code nonBlockingRetry=true} would NOT help, because {@code sendOnce}
 * overrides it.
 *
 * <p>Small and bounded: at most {@value #MAX_THREADS} threads, a {@value #QUEUE_CAPACITY}-slot
 * queue, and a rejection handler that DROPS with a WARN. Dropping is deliberate --
 * {@code CallerRunsPolicy} would hand a ~33s blocking send back to the request thread, which is the
 * exact failure this bean exists to prevent. A dropped notification costs a channel message; the
 * booking is already committed and emailed before any of this runs.
 */
@ApplicationScoped
public class DeliveryExecutor {

    private static final int CORE_THREADS = 2;

    private static final int MAX_THREADS = 6;

    private static final int QUEUE_CAPACITY = 1000;

    private final ExecutorService pool;

    private final NotificationOptions options;

    public DeliveryExecutor() {
        this.pool = new ThreadPoolExecutor(
                CORE_THREADS,
                MAX_THREADS,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                // Daemon threads: a stuck send must never hold JVM shutdown open.
                Thread.ofPlatform().name("calit-notify-", 0).daemon(true).factory(),
                (r, e) -> Log.warn("channel delivery dropped: delivery queue full"));
        this.options = NotificationOptions.ofExecutor(pool);
    }

    /** Pass to {@code Event.fireAsync} so the delivery never lands on the shared worker pool. */
    public NotificationOptions options() {
        return options;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
