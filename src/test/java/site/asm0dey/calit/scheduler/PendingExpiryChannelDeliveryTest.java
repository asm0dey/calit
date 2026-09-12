package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.notify.NotificationChannel;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * An auto-expired approval must reach the host's channel too. The expiry tick declines the booking
 * and enqueues the declined email itself without firing {@code BookingDeclined}, so — exactly like
 * the reminder tick — the channel notification is dispatched explicitly after its transaction
 * commits, and an owner-initiated decline would otherwise be the only decline a channel ever sees.
 * The stub binds an ephemeral port.
 */
@QuarkusTest
class PendingExpiryChannelDeliveryTest {

    /** Ephemeral: bound to 0 and read back in {@link #startStub()}, so nothing on the box can collide. */
    static int port;

    static HttpServer server;
    static CountDownLatch hit;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            hit.countDown();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
    }

    @Inject
    PendingExpiryScheduler scheduler;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        hit = new CountDownLatch(1);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
    }

    @Test
    void anAutoExpiredApprovalReachesTheHostsChannel() throws InterruptedException {
        var channelId = seed();

        scheduler.expirePendingBookings();

        assertTrue(hit.await(10, TimeUnit.SECONDS), "the auto-expired decline never reached the channel");
        assertNotNull(awaitStamp(channelId).lastSuccessAt);
    }

    /** Owner 1 with settings, a PENDING booking already past the 24h hold, and one channel. */
    private Long seed() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "Owner");
            MeetingType type = MultiHostFixtures.meetingType(1L, "expire-me-" + System.nanoTime(), 30);

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = type.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "sam@example.com";
            var start = Instant.now().plus(500, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.PENDING;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now().minus(25, ChronoUnit.HOURS); // past the 24h hold -> expires
            b.persist();

            return MultiHostFixtures.channel(1L, "ntfy+http://localhost:" + port + "/calit", "Stub").id;
        });
    }

    // S2925: polling for an async DB write with no latch to observe it; a single fixed sleep would be worse.
    @SuppressWarnings("java:S2925")
    private NotificationChannel awaitStamp(Long channelId) throws InterruptedException {
        for (var i = 0; i < 100; i++) { // the timestamp write happens just after the POST returns
            NotificationChannel c =
                    QuarkusTransaction.requiringNew().call(() -> NotificationChannel.findById(channelId));
            if (c.lastSuccessAt != null) return c;
            Thread.sleep(50);
        }
        fail("delivery outcome was never stamped");
        return null;
    }
}
