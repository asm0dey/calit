package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

@QuarkusTest
class BookingServiceGuestTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        QuarkusTransaction.requiringNew().run(() -> {
            BookingGuest.deleteAll();
            Booking.deleteAll();
            MeetingType.delete("ownerId = ?1 and slug = ?2", 1L, "guest-svc");
            AvailabilityRule.delete("ownerId = ?1", 1L);
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Guest Svc";
            t.slug = "guest-svc";
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555";
            t.persist();
            for (DayOfWeek d : DayOfWeek.values()) {
                AvailabilityRule r = new AvailabilityRule();
                r.ownerId = 1L;
                r.dayOfWeek = d;
                r.startTime = LocalTime.of(0, 0);
                r.endTime = LocalTime.of(23, 59);
                r.meetingTypeId = null;
                r.persist();
            }
        });
    }

    /** The earliest available slot start for the seeded type (always tomorrow, so +1 h is never near end-of-day). */
    private Instant firstSlot() {
        MeetingType t = MeetingType.findBySlug(1L, "guest-svc");
        var from =
                java.time.LocalDate.now(java.time.ZoneId.of("Europe/Amsterdam")).plusDays(1);
        var slots = bookingService.availableSlots(t, from, from.plusDays(10));
        return slots.getFirst().start().toInstant();
    }

    @Test
    void bookPersistsGuestsDedupesDropsInviteeAndCaps() {
        // 12 raw entries: a dup (case-insensitive), the invitee's own email, a malformed one,
        // and enough valid ones to exceed the cap of 10.
        List<String> raw = new java.util.ArrayList<>();
        raw.add("ana@example.com");
        raw.add("ANA@example.com"); // case-insensitive dup -> dropped
        raw.add("sam@example.com"); // the invitee -> dropped
        raw.add("not-an-email"); // invalid -> dropped
        for (var i = 0; i < 12; i++) raw.add("g" + i + "@example.com"); // plenty, to hit the cap

        Booking b = bookingService.book(
                1L, "guest-svc", firstSlot(), "Sam", "sam@example.com", Map.of(), null, null, "en", raw);

        List<BookingGuest> guests = BookingGuest.activeForBooking(b.id);
        assertEquals(BookingService.MAX_GUESTS_PER_BOOKING, guests.size(), "capped at the max");
        assertTrue(guests.stream().noneMatch(g -> g.email.equalsIgnoreCase("sam@example.com")), "invitee dropped");
        assertTrue(guests.stream().noneMatch(g -> g.email.equals("not-an-email")), "invalid dropped");
        assertEquals(
                guests.size(),
                guests.stream().map(g -> g.email.toLowerCase()).distinct().count(),
                "deduped");
        assertTrue(guests.stream().allMatch(g -> g.ownerId.equals(1L)), "owner-scoped");
        assertTrue(
                guests.stream().allMatch(g -> g.declineToken != null && !g.declineToken.isBlank()), "decline tokens");
    }

    @Test
    void rescheduleReconcilesAddRemoveKeepAndBumpsSequence() {
        var start = firstSlot();
        Booking b = bookingService.book(
                1L,
                "guest-svc",
                start,
                "Sam",
                "sam@example.com",
                Map.of(),
                null,
                null,
                "en",
                List.of("ana@example.com", "bob@example.com"));
        assertEquals(0, b.icsSequence); // check in-memory; findById here would cache stale icsSequence in S_test

        // Move to a different free slot, drop bob, keep ana, add cyd.
        var newStart = start.plusSeconds(3600);
        bookingService.reschedule(b.manageToken, newStart, List.of("ana@example.com", "cyd@example.com"));

        List<BookingGuest> active = BookingGuest.activeForBooking(b.id);
        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(g -> g.email.equals("ana@example.com")), "ana kept");
        assertTrue(active.stream().anyMatch(g -> g.email.equals("cyd@example.com")), "cyd added");
        BookingGuest bob = BookingGuest.findInBooking(b.id, "bob@example.com");
        assertEquals(GuestStatus.REMOVED, bob.status, "bob removed");
        assertEquals(1, Booking.<Booking>findById(b.id).icsSequence, "sequence bumped once");
    }

    @Test
    void rescheduleWithNullGuestsLeavesGuestListUntouched() {
        var start = firstSlot();
        Booking b = bookingService.book(
                1L,
                "guest-svc",
                start,
                "Sam",
                "sam@example.com",
                Map.of(),
                null,
                null,
                "en",
                List.of("ana@example.com"));

        bookingService.reschedule(b.manageToken, start.plusSeconds(3600)); // 2-arg overload -> null guests

        assertEquals(1, BookingGuest.activeForBooking(b.id).size(), "guests preserved by the no-guest overload");
    }

    @Test
    void declineGuestMarksDeclinedAndIsIdempotent() throws Exception {
        Booking b = bookingService.book(
                1L,
                "guest-svc",
                firstSlot(),
                "Sam",
                "sam@example.com",
                Map.of(),
                null,
                null,
                "en",
                List.of("ana@example.com"));
        // Load in a fresh session so ana is NOT cached in S_test; otherwise declineGuest (separate session)
        // commits DECLINED but the next findById hits S_test's stale INVITED entry.
        BookingGuest ana = QuarkusTransaction.requiringNew()
                .call(() -> BookingGuest.activeForBooking(b.id).getFirst());

        bookingService.declineGuest(ana.declineToken);
        assertEquals(GuestStatus.DECLINED, BookingGuest.<BookingGuest>findById(ana.id).status);

        // Second call is a no-op, not an error.
        bookingService.declineGuest(ana.declineToken);
        assertEquals(GuestStatus.DECLINED, BookingGuest.<BookingGuest>findById(ana.id).status);
    }
}
