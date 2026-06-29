package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;

@QuarkusTest
class BookingTest {

    // meeting_type_id is a real FK; create a MeetingType inside each @TestTransaction
    // and use its generated id (a literal id would violate the FK constraint — same pattern as AvailabilityRuleTest).
    private Long createMeetingType(String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 30;
        t.persist();
        return t.id;
    }

    @Test
    @TestTransaction
    void persistsAndReadsBackAllFields() {
        var start = Instant.parse("2026-06-08T07:00:00Z");
        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = createMeetingType("all-fields-test");
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = start;
        b.endUtc = start.plusSeconds(1800);
        b.googleEventId = "evt-1";
        b.meetLink = "https://meet.google.com/abc-defg-hij";
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.parse("2026-06-01T10:00:00Z");
        b.manageToken = "11111111-2222-3333-4444-555555555555";
        b.answers = Map.of("description", "Quarterly sync", "phone", "+31201234567");
        b.persist();

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals("https://meet.google.com/abc-defg-hij", loaded.meetLink);
        assertEquals(start, loaded.startUtc);
        // Manage-token round-trips and is the invitee's reschedule/cancel key.
        assertEquals("11111111-2222-3333-4444-555555555555", loaded.manageToken);
        // Feature 10: custom answers round-trip through the JSONB column.
        assertEquals("Quarterly sync", loaded.answers.get("description"));
        assertEquals("+31201234567", loaded.answers.get("phone"));
    }

    @Test
    @TestTransaction
    void heldOverlappingFindsPendingAndConfirmedInWindow() {
        var base = Instant.parse("2026-06-08T07:00:00Z");
        // Non-overlapping held slots to satisfy the booking_no_overlap_held DB constraint
        // while still both falling within the 06:00-08:00 query window.
        persistBooking(base, base.plusSeconds(900), BookingStatus.CONFIRMED); // 07:00-07:15 held
        persistBooking(base.plusSeconds(900), base.plusSeconds(1800), BookingStatus.PENDING); // 07:15-07:30 held
        persistBooking(
                base.plusSeconds(7200), base.plusSeconds(9000), BookingStatus.CONFIRMED); // 09:00-09:30 (out of window)
        persistBooking(
                base.plusSeconds(3600),
                base.plusSeconds(4500),
                BookingStatus.CANCELLED); // 08:00-08:15 cancelled, ignored
        persistBooking(
                base.plusSeconds(4500),
                base.plusSeconds(5400),
                BookingStatus.DECLINED); // 08:15-08:30 declined, ignored

        // Window 06:00-08:00 catches the CONFIRMED + PENDING holds, not CANCELLED/DECLINED.
        List<Booking> hits = Booking.heldOverlapping(1L, base.minusSeconds(3600), base.plusSeconds(3600));

        assertEquals(2, hits.size());
        assertTrue(
                hits.stream().allMatch(x -> x.status == BookingStatus.PENDING || x.status == BookingStatus.CONFIRMED));
    }

    @Test
    @TestTransaction
    void findByManageTokenLoadsBooking() {
        persistBooking(
                Instant.parse("2026-06-08T07:00:00Z"),
                Instant.parse("2026-06-08T07:30:00Z"),
                BookingStatus.PENDING,
                "tok-abc");

        Booking loaded = Booking.findByManageToken("tok-abc");

        assertEquals(BookingStatus.PENDING, loaded.status);
    }

    private void persistBooking(Instant start, Instant end, BookingStatus status) {
        persistBooking(start, end, status, java.util.UUID.randomUUID().toString());
    }

    private void persistBooking(Instant start, Instant end, BookingStatus status, String token) {
        Booking b = new Booking();
        b.ownerId = 1L;
        // meeting_type_id is a real FK; create a MeetingType using the token as a unique slug suffix.
        b.meetingTypeId = createMeetingType("bk-" + token.replace("-", ""));
        b.inviteeName = "X";
        b.inviteeEmail = "x@example.com";
        b.startUtc = start;
        b.endUtc = end;
        b.status = status;
        b.createdAt = Instant.now();
        b.manageToken = token;
        b.persist();
    }
}
