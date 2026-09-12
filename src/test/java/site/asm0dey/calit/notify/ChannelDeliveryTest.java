package site.asm0dey.calit.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * End-to-end delivery: a real booking event reaches a real HTTP endpoint through notify4j, and the
 * outcome is stamped on the channel row. The stub is a JDK {@link HttpServer} (the pattern
 * {@code CaptchaVerifierTurnstileTest} already uses — no new test dependency), and the test awaits a
 * {@link CountDownLatch} because {@code fireAsync} races the assertion.
 */
@QuarkusTest
class ChannelDeliveryTest {

    /** Ephemeral: bound to 0 and read back in {@link #startStub()}, so nothing on the box can collide. */
    static int port;

    static HttpServer server;
    static CountDownLatch hit;
    static final AtomicInteger status = new AtomicInteger(200);

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1);
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

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        hit = new CountDownLatch(1);
        status.set(200);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
    }

    private Long channel(long ownerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var c = new NotificationChannel();
            c.ownerId = ownerId;
            c.url = "ntfy+http://localhost:" + port + "/calit";
            c.label = "Stub";
            c.createdAt = Instant.now();
            c.persist();
            return c.id;
        });
    }

    private Long booking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.settings(1L, "Owner");
            MeetingType type = MultiHostFixtures.meetingType(1L, "deliver-me", 30);
            var start = Instant.parse("2026-06-08T09:00:00Z");
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = type.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "sam@example.com";
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = "tok-deliver-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }

    /** AFTER_SUCCESS observers need a committed transaction to fire from. */
    private void fireConfirmed(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> confirmed.fire(new BookingConfirmed(bookingId)));
    }

    // S2925: polling for an async DB write with no latch to observe it; a single fixed sleep would be worse.
    @SuppressWarnings("java:S2925")
    private NotificationChannel await(Long channelId, boolean success) throws InterruptedException {
        assertTrue(hit.await(10, TimeUnit.SECONDS), "the stub never received a POST");
        for (var i = 0; i < 100; i++) { // the timestamp write happens just after the POST returns
            NotificationChannel c =
                    QuarkusTransaction.requiringNew().call(() -> NotificationChannel.findById(channelId));
            if ((success ? c.lastSuccessAt : c.lastFailureAt) != null) return c;
            Thread.sleep(50);
        }
        fail("delivery outcome was never stamped");
        return null;
    }

    @Test
    void aConfirmedBookingIsDeliveredAndStamped() throws InterruptedException {
        var channelId = channel(1L);
        fireConfirmed(booking());

        NotificationChannel c = await(channelId, true);
        assertNotNull(c.lastSuccessAt);
        assertNull(c.lastFailureAt);
    }

    @Test
    void aFailingChannelStampsTheFailureAndNotTheSuccess() throws InterruptedException {
        status.set(500);
        var channelId = channel(1L);
        fireConfirmed(booking());

        NotificationChannel c = await(channelId, false);
        assertNotNull(c.lastFailureAt);
        assertNull(c.lastSuccessAt);
    }

    @Test
    void anotherOwnersChannelIsNeverDelivered() throws InterruptedException {
        // Another owner's channel; the booking below belongs to owner 1. notification_channel.owner_id
        // is FK-constrained to app_user, so the second owner has to actually exist.
        Long other = QuarkusTransaction.requiringNew().call(() -> MultiHostFixtures.enabledUser("other-owner").id);
        channel(other);
        fireConfirmed(booking());

        assertFalse(hit.await(2, TimeUnit.SECONDS), "another owner's channel must not receive owner 1's booking");
    }
}
