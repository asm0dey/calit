package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingDetailsChanged;
import site.asm0dey.calit.booking.events.GuestRemoved;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarRef;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.google.GoogleCredential;
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

    /**
     * Admin (id 1, "pasha") as creator + a second accepted co-host ("volodya"), both with rules covering Monday.
     */
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
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("grp-evt", "meet", "cal", null));
    }

    /**
     * Seed a real GoogleCredential row so a group event row's google_credential_id FK holds.
     */
    private static GoogleCredential seedCredential(String sub) {
        GoogleCredential cred = new GoogleCredential();
        cred.ownerId = 1L;
        cred.refreshToken = "rt";
        cred.googleSub = sub;
        cred.persist();
        return cred;
    }

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditWritesTitleToAllRowsPatchesEventOnceAndReconcilesGuestsOnLeadOnly() {
        stubOrganizerOnCreator();
        // Report a real stored calendar ref (not the null pre-V26 shape stubOrganizerOnCreator()
        // defaults to) so the group's event row carries one, and the later patch can be verified
        // to address it specifically rather than "whatever ref, if any".
        GoogleCredential cred = seedCredential("sub-group-details");
        CalendarRef ref = new CalendarRef(cred.id, "grp-cal@example.com");
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("grp-evt", "meet", "cal", ref));
        // auto-confirm -> event created immediately
        groupType();

        Booking lead =
                bookingService.book(
                        1L,
                        "intro",
                        nextMonday(10),
                        "Sam",
                        "sam@x.com",
                        Map.of(),
                        "tok",
                        "",
                        "en",
                        List.of()
        );
        List<Booking> rows = Booking.group(lead.groupId);
        assertThat(rows).hasSize(2);
        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        var detailsChangedBefore = DETAILS_CHANGED.get();
        Map<Long, Integer> seqBefore = new java.util.HashMap<>();
        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> seqBefore.put(r.id, r.icsSequence));
        // The co-host (not the creator/organizer) initiates the edit -> keyed by ITS manageToken.
        Booking cohostRow = rows
            .stream()
            .filter(r -> !r.ownerId.equals(1L))
            .findFirst()
            .orElseThrow();
        bookingService.updateDetails(cohostRow.manageToken, "Roadmap sync", "Q3 planning", List.of("ana@x.com"), true);
        // title/description written to every group row, identically.
        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertThat(r.title).isEqualTo("Roadmap sync");
            assertThat(r.description).isEqualTo("Q3 planning");
        });
        // Review fix 1: every group row's iTIP SEQUENCE bumps on a detail edit, exactly like
        // rescheduleGroup, so a resent guest .ics supersedes the prior one in calendar clients.
        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> assertThat(r.icsSequence)
                .as("icsSequence must bump on row " + r.id)
                .isEqualTo(seqBefore.get(r.id) + 1));
        // the shared Google event is patched exactly once, via the organizer (creator, owner id 1),
        // addressed at the ref the event was actually created on.
        verify(calendarPort, times(1))
            .updateEventDetails(
                    eq(1L),
                    eq(ref),
                    eq("grp-evt"),
                    eq("Roadmap sync with Sam"),
                    eq("Q3 planning"),
                    anyList()
            );
        // guests reconcile on the lead row only.
        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        List<BookingGuest> leadGuests = BookingGuest.activeForBooking(freshLead.id);
        assertThat(leadGuests).hasSize(1);
        assertThat(leadGuests.getFirst().email).isEqualTo("ana@x.com");

        Booking cohostAfter =
                Booking
            .<Booking>group(lead.groupId)
            .stream()
            .filter(r -> !r.ownerId.equals(1L))
            .findFirst()
            .orElseThrow();
        assertThat(BookingGuest.activeForBooking(cohostAfter.id)).as("guests never attach to a non-lead row").isEmpty();
        // exactly one BookingDetailsChanged fired, keyed by the lead.
        assertThat(DETAILS_CHANGED.get()).isEqualTo(detailsChangedBefore + 1);
    }

    // --- final-review fix: editing details on a group whose organizer has since disconnected
    // Google must still succeed (rows updated locally) without attempting the remote
    // updateEventDetails call, mirroring updateDetails's single-host isConnected guard ---
    @Test
    @io.quarkus.test.TestTransaction
    void groupEditWhoseOrganizerDisconnectedGoogleUpdatesRowsWithoutCallingUpdateEventDetails() {
        stubOrganizerOnCreator();
        // auto-confirm -> event created immediately
        groupType();

        Booking lead =
                bookingService.book(
                        1L,
                        "intro",
                        nextMonday(10),
                        "Sam",
                        "sam@x.com",
                        Map.of(),
                        "tok",
                        "",
                        "en",
                        List.of()
        );
        List<Booking> rows = Booking.group(lead.groupId);
        assertThat(rows).hasSize(2);
        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
        // The organizer (creator, owner id 1) disconnects Google after confirmation.
        when(calendarPort.isConnected(1L)).thenReturn(false);

        var detailsChangedBefore = DETAILS_CHANGED.get();
        Booking cohostRow = rows
            .stream()
            .filter(r -> !r.ownerId.equals(1L))
            .findFirst()
            .orElseThrow();
        assertDoesNotThrow(() -> bookingService.updateDetails(
                cohostRow.manageToken,
                "Roadmap sync",
                "Q3 planning",
                List.of("ana@x.com"),
                true
        ));

        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertThat(r.title).isEqualTo("Roadmap sync");
            assertThat(r.description).isEqualTo("Q3 planning");
        });
        verify(calendarPort, never()).updateEventDetails(
                anyLong(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                anyList()
        );
        assertThat(DETAILS_CHANGED.get()).isEqualTo(detailsChangedBefore + 1);
    }

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditDroppingAGuestFiresGuestRemoved() {
        stubOrganizerOnCreator();
        // auto-confirm -> event created immediately
        groupType();

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
                List.of("ana@x.com", "ben@x.com")
        );

        var guestRemovedBefore = GUEST_REMOVED.get();
        // Review fix 2: dropping a guest via a group detail-edit must fire GuestRemoved for the
        // dropped guest, exactly like rescheduleGroup -- so they get a cancellation, not silence.
        bookingService.updateDetails(lead.manageToken, lead.title, lead.description, List.of("ana@x.com"), true);

        assertThat(GUEST_REMOVED.get()).isEqualTo(guestRemovedBefore + 1);

        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        List<BookingGuest> activeGuests = BookingGuest.activeForBooking(freshLead.id);
        assertThat(activeGuests).hasSize(1);
        assertThat(activeGuests.getFirst().email).isEqualTo("ana@x.com");
    }

    @Test
    @io.quarkus.test.TestTransaction
    void groupEditWhenNoHostEverHadGoogleSkipsTheRemotePatch() {
        // Neither host is Google-connected at booking time, so createGroupGoogleEvent's organizer
        // lookup comes back null and no row ever gets a googleEventId -> groupEventRow(...) is null.
        // updateGroupDetails's `eventRow != null && ...` guard must short-circuit on that null, not
        // dereference eventRow.ownerId.
        groupType();
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        Booking lead =
                bookingService.book(
                        1L,
                        "intro",
                        nextMonday(10),
                        "Sam",
                        "sam@x.com",
                        Map.of(),
                        "tok",
                        "",
                        "en",
                        List.of()
        );
        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> assertThat(r.googleEventId).isNull());

        assertDoesNotThrow(() -> bookingService.updateDetails(
                lead.manageToken,
                "Roadmap sync",
                "Q3 planning",
                List.of("ana@x.com"),
                true
        ));

        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertThat(r.title).isEqualTo("Roadmap sync");
            assertThat(r.description).isEqualTo("Q3 planning");
        });
        verify(calendarPort, never()).updateEventDetails(
                anyLong(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                anyList()
        );
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
        when(calendarPort.createEvent(eq(v.id), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("grp-evt-cohost", "meet", "cal", null));

        Booking lead =
                bookingService.book(
                        1L,
                        "intro",
                        nextMonday(10),
                        "Sam",
                        "sam@x.com",
                        Map.of(),
                        "tok",
                        "",
                        "en",
                        List.of()
        );

        bookingService.updateDetails(lead.manageToken, "Cohost organized", "desc", List.of(), true);

        verify(calendarPort, times(1))
            .updateEventDetails(
                    eq(v.id),
                    any(),
                    eq("grp-evt-cohost"),
                    eq("Cohost organized with Sam"),
                    eq("desc"),
                    anyList()
            );
    }
}
