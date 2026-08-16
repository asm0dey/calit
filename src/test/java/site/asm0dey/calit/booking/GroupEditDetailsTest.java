package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingDetailsChanged;
import site.asm0dey.calit.booking.events.GuestRemoved;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 12: group edit — title/description write to every group row, guests reconcile on the lead row
 * only, and the one shared Google event is patched exactly once (via the organizer's row).
 */
@QuarkusTest
class GroupEditDetailsTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    static final AtomicInteger DETAILS_CHANGED = new AtomicInteger();
    static final AtomicInteger GUEST_REMOVED = new AtomicInteger();

    void onDetailsChanged(@Observes BookingDetailsChanged e) {
        DETAILS_CHANGED.incrementAndGet();
    }

    void onGuestRemoved(@Observes GuestRemoved e) {
        GUEST_REMOVED.incrementAndGet();
    }

    private Instant nextMonday(int hour) {
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(hour, 0).atZone(AMS).toInstant();
    }

    /** Admin (id 1, "pasha") as creator + a second accepted co-host ("volodya"), both with rules covering Monday. */
    private MeetingType groupType() {
        MultiHostFixtures.settings(1L, "pasha");
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.settings(v.id, "volodya");
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
        return MultiHostFixtures.acceptedTwoHostType(1L, v.id, "intro", 60, false);
    }

    private void stubOrganizerOnCreator() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("grp-evt", "meet", "cal"));
    }

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditWritesTitleToAllRowsPatchesEventOnceAndReconcilesGuestsOnLeadOnly() {
        stubOrganizerOnCreator();
        groupType(); // auto-confirm -> event created immediately

        Booking lead = bookingService.book(
                1L, "intro", nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());
        verify(calendarPort, times(1))
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        var detailsChangedBefore = DETAILS_CHANGED.get();
        Map<Long, Integer> seqBefore = new java.util.HashMap<>();
        Booking.<Booking>group(lead.groupId).forEach(r -> seqBefore.put(r.id, r.icsSequence));

        // The co-host (not the creator/organizer) initiates the edit -> keyed by ITS manageToken.
        Booking cohostRow =
                rows.stream().filter(r -> !r.ownerId.equals(1L)).findFirst().orElseThrow();
        bookingService.updateDetails(cohostRow.manageToken, "Roadmap sync", "Q3 planning", List.of("ana@x.com"), true);

        // title/description written to every group row, identically.
        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertEquals("Roadmap sync", r.title);
            assertEquals("Q3 planning", r.description);
        });

        // Review fix 1: every group row's iTIP SEQUENCE bumps on a detail edit, exactly like
        // rescheduleGroup, so a resent guest .ics supersedes the prior one in calendar clients.
        Booking.<Booking>group(lead.groupId)
                .forEach(r ->
                        assertEquals(seqBefore.get(r.id) + 1, r.icsSequence, "icsSequence must bump on row " + r.id));

        // the shared Google event is patched exactly once, via the organizer (creator, owner id 1).
        verify(calendarPort, times(1))
                .updateEventDetails(
                        eq(1L), any(), eq("grp-evt"), eq("Roadmap sync with Sam"), eq("Q3 planning"), anyList());

        // guests reconcile on the lead row only.
        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        List<BookingGuest> leadGuests = BookingGuest.activeForBooking(freshLead.id);
        assertEquals(1, leadGuests.size());
        assertEquals("ana@x.com", leadGuests.getFirst().email);

        Booking cohostAfter = Booking.<Booking>group(lead.groupId).stream()
                .filter(r -> !r.ownerId.equals(1L))
                .findFirst()
                .orElseThrow();
        assertTrue(BookingGuest.activeForBooking(cohostAfter.id).isEmpty(), "guests never attach to a non-lead row");

        // exactly one BookingDetailsChanged fired, keyed by the lead.
        assertEquals(detailsChangedBefore + 1, DETAILS_CHANGED.get());
    }

    // --- final-review fix: editing details on a group whose organizer has since disconnected
    // Google must still succeed (rows updated locally) without attempting the remote
    // updateEventDetails call, mirroring updateDetails's single-host isConnected guard ---

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditWhoseOrganizerDisconnectedGoogleUpdatesRowsWithoutCallingUpdateEventDetails() {
        stubOrganizerOnCreator();
        groupType(); // auto-confirm -> event created immediately

        Booking lead = bookingService.book(
                1L, "intro", nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());
        List<Booking> rows = Booking.group(lead.groupId);
        assertEquals(2, rows.size());
        verify(calendarPort, times(1))
                .createEvent(anyLong(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        // The organizer (creator, owner id 1) disconnects Google after confirmation.
        when(calendarPort.isConnected(1L)).thenReturn(false);

        var detailsChangedBefore = DETAILS_CHANGED.get();
        Booking cohostRow =
                rows.stream().filter(r -> !r.ownerId.equals(1L)).findFirst().orElseThrow();
        assertDoesNotThrow(() -> bookingService.updateDetails(
                cohostRow.manageToken, "Roadmap sync", "Q3 planning", List.of("ana@x.com"), true));

        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertEquals("Roadmap sync", r.title);
            assertEquals("Q3 planning", r.description);
        });
        verify(calendarPort, never())
                .updateEventDetails(anyLong(), any(), anyString(), anyString(), anyString(), anyList());
        assertEquals(detailsChangedBefore + 1, DETAILS_CHANGED.get());
    }

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditDroppingAGuestFiresGuestRemoved() {
        stubOrganizerOnCreator();
        groupType(); // auto-confirm -> event created immediately

        Booking lead = bookingService.book(
                1L,
                "intro",
                nextMonday(11),
                "Sam",
                "sam@x.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of("ana@x.com", "ben@x.com"));

        var guestRemovedBefore = GUEST_REMOVED.get();

        // Review fix 2: dropping a guest via a group detail-edit must fire GuestRemoved for the
        // dropped guest, exactly like rescheduleGroup -- so they get a cancellation, not silence.
        bookingService.updateDetails(lead.manageToken, lead.title, lead.description, List.of("ana@x.com"), true);

        assertEquals(guestRemovedBefore + 1, GUEST_REMOVED.get());

        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        List<BookingGuest> activeGuests = BookingGuest.activeForBooking(freshLead.id);
        assertEquals(1, activeGuests.size());
        assertEquals("ana@x.com", activeGuests.getFirst().email);
    }

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditByCohostOrganizerPatchesEventViaCohostOwnerId() {
        // Creator NOT connected to Google, co-host IS -> co-host is the organizer of the shared event.
        MultiHostFixtures.settings(1L, "pasha");
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.settings(v.id, "volodya");
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.acceptedTwoHostType(1L, v.id, "intro", 60, false);

        when(calendarPort.isConnected(1L)).thenReturn(false);
        when(calendarPort.isConnected(v.id)).thenReturn(true);
        when(calendarPort.createEvent(eq(v.id), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("grp-evt-cohost", "meet", "cal"));

        Booking lead = bookingService.book(
                1L, "intro", nextMonday(10), "Sam", "sam@x.com", Map.of(), "tok", "", "en", List.of());

        bookingService.updateDetails(lead.manageToken, "Cohost organized", "desc", List.of(), true);

        verify(calendarPort, times(1))
                .updateEventDetails(
                        eq(v.id), any(), eq("grp-evt-cohost"), eq("Cohost organized with Sam"), eq("desc"), anyList());
    }
}
