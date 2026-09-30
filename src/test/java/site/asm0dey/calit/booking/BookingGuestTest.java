package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;

@QuarkusTest
class BookingGuestTest {
    private Long createBooking() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "g";
        t.slug = "g-" + System.nanoTime();
        t.durationMinutes = 30;
        t.persist();
        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = t.id;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = Instant.parse("2026-06-08T07:00:00Z");
        b.endUtc = b.startUtc.plusSeconds(1800);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = java.util.UUID.randomUUID().toString();
        b.persist();
        return b.id;
    }

    private BookingGuest guest(Long bookingId, String email, GuestStatus status) {
        BookingGuest g = new BookingGuest();
        g.ownerId = 1L;
        g.bookingId = bookingId;
        g.email = email;
        g.status = status;
        g.declineToken = java.util.UUID.randomUUID().toString();
        g.createdAt = Instant.now();
        g.persist();
        return g;
    }

    @Test
    @TestTransaction
    void persistsAndReadsBackGuestFields() {
        var bookingId = createBooking();
        BookingGuest g = guest(bookingId, "ana@example.com", GuestStatus.INVITED);

        BookingGuest loaded = BookingGuest.findById(g.id);
        assertThat(loaded.ownerId).isOne();
        assertThat(loaded.bookingId).isEqualTo(bookingId);
        assertThat(loaded.email).isEqualTo("ana@example.com");
        assertThat(loaded.status).isEqualTo(GuestStatus.INVITED);
        assertThat(loaded.declineToken).isEqualTo(g.declineToken);
    }

    @Test
    @TestTransaction
    void activeForBookingReturnsOnlyInvited() {
        var bookingId = createBooking();
        guest(bookingId, "ana@example.com", GuestStatus.INVITED);
        guest(bookingId, "bob@example.com", GuestStatus.DECLINED);
        guest(bookingId, "cyd@example.com", GuestStatus.REMOVED);

        List<BookingGuest> active = BookingGuest.activeForBooking(bookingId);
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().email).isEqualTo("ana@example.com");
        assertThat(BookingGuest.allForBooking(bookingId)).hasSize(3);
    }

    @Test
    @TestTransaction
    void findByDeclineTokenAndFindInBookingResolve() {
        var bookingId = createBooking();
        BookingGuest g = guest(bookingId, "Ana@Example.com", GuestStatus.INVITED);

        assertThat(BookingGuest.findByDeclineToken(g.declineToken).id).isEqualTo(g.id);
        // findInBooking is case-insensitive on email
        assertThat(BookingGuest.findInBooking(bookingId, "ana@example.com").id).isEqualTo(g.id);
        assertThat(BookingGuest.findInBooking(bookingId, "nobody@example.com")).isNull();
    }

    @Test
    @TestTransaction
    void bookingIcsSequenceDefaultsToZeroAndRoundTrips() {
        var bookingId = createBooking();
        Booking b = Booking.findById(bookingId);
        assertThat(b.icsSequence).isZero();
        b.icsSequence = 2;
        b.persistAndFlush();
        assertThat(Booking.<Booking>findById(bookingId).icsSequence).isEqualTo(2);
    }
}
