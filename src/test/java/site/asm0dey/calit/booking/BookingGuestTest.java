package site.asm0dey.calit.booking;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BookingGuestTest {

    private Long createBooking() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L; t.name = "g"; t.slug = "g-" + System.nanoTime(); t.durationMinutes = 30;
        t.persist();
        Booking b = new Booking();
        b.ownerId = 1L; b.meetingTypeId = t.id; b.inviteeName = "Sam"; b.inviteeEmail = "sam@example.com";
        b.startUtc = Instant.parse("2026-06-08T07:00:00Z"); b.endUtc = b.startUtc.plusSeconds(1800);
        b.status = BookingStatus.CONFIRMED; b.createdAt = Instant.now();
        b.manageToken = java.util.UUID.randomUUID().toString();
        b.persist();
        return b.id;
    }

    private BookingGuest guest(Long bookingId, String email, GuestStatus status) {
        BookingGuest g = new BookingGuest();
        g.ownerId = 1L; g.bookingId = bookingId; g.email = email; g.status = status;
        g.declineToken = java.util.UUID.randomUUID().toString(); g.createdAt = Instant.now();
        g.persist();
        return g;
    }

    @Test
    @TestTransaction
    void persistsAndReadsBackGuestFields() {
        Long bookingId = createBooking();
        BookingGuest g = guest(bookingId, "ana@example.com", GuestStatus.INVITED);

        BookingGuest loaded = BookingGuest.findById(g.id);
        assertEquals(1L, loaded.ownerId);
        assertEquals(bookingId, loaded.bookingId);
        assertEquals("ana@example.com", loaded.email);
        assertEquals(GuestStatus.INVITED, loaded.status);
        assertEquals(g.declineToken, loaded.declineToken);
    }

    @Test
    @TestTransaction
    void activeForBookingReturnsOnlyInvited() {
        Long bookingId = createBooking();
        guest(bookingId, "ana@example.com", GuestStatus.INVITED);
        guest(bookingId, "bob@example.com", GuestStatus.DECLINED);
        guest(bookingId, "cyd@example.com", GuestStatus.REMOVED);

        List<BookingGuest> active = BookingGuest.activeForBooking(bookingId);
        assertEquals(1, active.size());
        assertEquals("ana@example.com", active.getFirst().email);
        assertEquals(3, BookingGuest.allForBooking(bookingId).size());
    }

    @Test
    @TestTransaction
    void findByDeclineTokenAndFindInBookingResolve() {
        Long bookingId = createBooking();
        BookingGuest g = guest(bookingId, "Ana@Example.com", GuestStatus.INVITED);

        assertEquals(g.id, BookingGuest.findByDeclineToken(g.declineToken).id);
        // findInBooking is case-insensitive on email
        assertEquals(g.id, BookingGuest.findInBooking(bookingId, "ana@example.com").id);
        assertNull(BookingGuest.findInBooking(bookingId, "nobody@example.com"));
    }

    @Test
    @TestTransaction
    void bookingIcsSequenceDefaultsToZeroAndRoundTrips() {
        Long bookingId = createBooking();
        Booking b = Booking.findById(bookingId);
        assertEquals(0, b.icsSequence);
        b.icsSequence = 2;
        b.persistAndFlush();
        assertEquals(2, Booking.<Booking>findById(bookingId).icsSequence);
    }
}
