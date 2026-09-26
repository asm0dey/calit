package site.asm0dey.calit.email;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;

/**
 * V34's {@code email_outbox.booking_id}/{@code owner_id} columns carry real FOREIGN KEYs, so a tag
 * can only reference a booking/owner that actually exists (admin, seeded as id 1 by
 * DatabaseResetCallback, plus fixtures created here for a second owner/booking).
 */
@QuarkusTest
class OutboxTagTest {
    @Test
    @Transactional
    void enqueueStoresTheTag() {
        var bookingId = createBooking(1L);
        Long id = EmailOutbox.enqueue(
                "a@example.com",
                "s",
                "<p>hi</p>",
                null,
                null,
                "test",
                MailTag.forBooking(bookingId, 1L)
        );
        EmailOutbox row = EmailOutbox.findById(id);
        assertEquals(bookingId, row.bookingId);
        assertEquals(1L, row.ownerId);
    }

    @Test
    @Transactional
    void untaggedEnqueueLeavesBothLinksNull() {
        Long id = EmailOutbox.enqueue("a@example.com", "s", "<p>hi</p>", null, null, "test");
        EmailOutbox row = EmailOutbox.findById(id);
        assertNull(row.bookingId);
        assertNull(row.ownerId);
    }

    @Test
    @Transactional
    void deleteForBookingTakesOnlyThatBookingsRows() {
        var ownerTwoId = createOwner("second-owner");
        var bookingOneId = createBooking(1L);
        var bookingTwoId = createBooking(ownerTwoId);
        EmailOutbox.enqueue("a@example.com", "s", "<p>a</p>", null, null, "t", MailTag.forBooking(bookingOneId, 1L));
        EmailOutbox.enqueue(
                "a@example.com",
                "s",
                "<p>b</p>",
                null,
                null,
                "t",
                MailTag.forBooking(bookingTwoId, ownerTwoId)
        );
        assertEquals(1L, EmailOutbox.deleteForBooking(bookingOneId));
        assertEquals(0L, EmailOutbox.count("bookingId", bookingOneId));
        assertEquals(1L, EmailOutbox.count("bookingId", bookingTwoId));
    }

    @Test
    @Transactional
    void deleteForOwnerTakesOnlyThatOwnersRows() {
        var ownerTwoId = createOwner("second-owner");
        EmailOutbox.enqueue("a@example.com", "s", "<p>a</p>", null, null, "t", MailTag.forOwner(1L));
        EmailOutbox.enqueue("a@example.com", "s", "<p>b</p>", null, null, "t", MailTag.forOwner(ownerTwoId));
        assertEquals(1L, EmailOutbox.deleteForOwner(1L));
        assertEquals(1L, EmailOutbox.count("ownerId", ownerTwoId));
    }

    // --- fixtures: booking_id/owner_id are real FKs (V34), so a tag needs a real row behind it. ---
    private Long createOwner(String username) {
        AppUser u = AppUser.create(username, null, false);
        u.persist();
        OwnerSettings.seed(u.id, username + "@example.com");
        return u.id;
    }

    private Long createBooking(Long ownerId) {
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "outbox-tag-test";
        t.slug = "outbox-tag-test-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.persist();

        var start = Instant.parse("2026-06-08T07:00:00Z");
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = t.id;
        b.inviteeName = "Invitee";
        b.inviteeEmail = "invitee@example.com";
        b.startUtc = start;
        b.endUtc = start.plusSeconds(1800);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.persist();
        return b.id;
    }
}
