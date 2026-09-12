package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.enterprise.event.Event;
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
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * A channel saved while its scheme was allowed stops delivering once the operator drops that scheme
 * from {@code calit.notify.allowed-schemes} — and the drop is RECORDED. Without the failure stamp
 * the settings page would keep rendering the stale green "last delivery OK" badge for a channel that
 * has silently gone quiet, which is the one thing that UI exists to prevent.
 *
 * <p>Spying {@link NotifyConfig} rather than restarting Quarkus under a {@code @TestProfile} is the
 * shape {@code ChannelPolicyTest} already uses. The stub binds an ephemeral port.
 */
@QuarkusTest
class ChannelAllowlistDeliveryTest {

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
    Event<BookingConfirmed> confirmed;

    @InjectSpy
    NotifyConfig config;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        hit = new CountDownLatch(1);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
    }

    @Test
    void aChannelWhoseSchemeLeftTheAllowlistStopsDeliveringAndTheFailureIsRecorded() throws InterruptedException {
        // Saved while "ntfy" was allowed: the row exists and carries no failure yet.
        var channelId = seedChannel();
        var bookingId = seedBooking();
        // Operator tightens the allowlist afterwards.
        when(config.schemeAllowed("ntfy")).thenReturn(false);

        QuarkusTransaction.requiringNew().run(() -> confirmed.fire(new BookingConfirmed(bookingId)));

        assertFalse(hit.await(2, TimeUnit.SECONDS), "a channel whose scheme left the allowlist must not be delivered");
        NotificationChannel c = awaitFailureStamp(channelId);
        assertNotNull(c.lastFailureAt, "the skip must be visible on /me/settings, not only in the log");
        assertNull(c.lastSuccessAt);
    }

    private Long seedChannel() {
        return QuarkusTransaction.requiringNew()
                .call(() -> MultiHostFixtures.channel(1L, "ntfy+http://localhost:" + port + "/calit", "Stub").id);
    }

    private Long seedBooking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "Owner");
            MeetingType type = MultiHostFixtures.meetingType(1L, "blocked-" + System.nanoTime(), 30);
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = type.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "sam@example.com";
            var start = Instant.now().plus(500, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }

    // S2925: polling for an async DB write with no latch to observe it; a single fixed sleep would be worse.
    @SuppressWarnings("java:S2925")
    private NotificationChannel awaitFailureStamp(Long channelId) throws InterruptedException {
        for (var i = 0; i < 100; i++) {
            NotificationChannel c =
                    QuarkusTransaction.requiringNew().call(() -> NotificationChannel.findById(channelId));
            if (c.lastFailureAt != null) return c;
            Thread.sleep(50);
        }
        fail("the policy skip was never stamped as a failure");
        return null;
    }
}
