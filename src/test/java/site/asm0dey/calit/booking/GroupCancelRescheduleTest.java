package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingRescheduled;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.test.MultiHostFixtures;
import site.asm0dey.calit.user.AppUser;

/**
 * Task 11: group cancel + group reschedule with re-approval (initiator-aware), plus the deliberate
 * single-host reschedule behavior change ({@code reApproval = requiresApproval && !byOwner}).
 */
@QuarkusTest
class GroupCancelRescheduleTest {
    @Inject
    BookingService bookingService;
    @InjectMock
    CalendarPort calendarPort;
    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    // CDI observer counting fired BookingRescheduled events (same pattern as BookServiceTest).
    static final AtomicInteger RESCHEDULED = new AtomicInteger();

    void onRescheduled(@Observes BookingRescheduled e) {
        RESCHEDULED.incrementAndGet();
    }

    private Instant nextMonday(int hour) {
        var mon = LocalDate.now(AMS).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return mon.atTime(hour, 0).atZone(AMS).toInstant();
    }

    /**
     * Admin (id 1, "pasha") as creator + a second accepted co-host ("volodya"), both with rules covering Monday.
     */
    private MeetingType groupType(boolean approval) {
        MultiHostFixtures.settings(1L, "pasha");
        AppUser v = MultiHostFixtures.enabledUser("volodya");
        MultiHostFixtures.settings(v.id, "volodya");
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MultiHostFixtures.rule(v.id, DayOfWeek.MONDAY, 9, 17);
        return MultiHostFixtures.acceptedTwoHostType(1L, v.id, "intro", 60, approval);
    }

    private MeetingType singleHostType(String slug, boolean approval) {
        MultiHostFixtures.settings(1L, "pasha");
        MultiHostFixtures.rule(1L, DayOfWeek.MONDAY, 9, 17);
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.horizonDays = 50_000;
        t.requiresApproval = approval;
        t.persist();
        return t;
    }

    private void stubOrganizerOnCreator() {
        when(calendarPort.isConnected(1L)).thenReturn(true);
        when(calendarPort.isConnected(argThat(id -> id != null && id != 1L))).thenReturn(false);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt", "meet", "cal", null));
    }

    // --- (a) cancel: any host cancels -> all rows CANCELLED, deleteEvent called once ---
    @Test
    @TestTransaction
    void anyHostCancelMarksAllRowsCancelledAndDeletesEventOnce() {
        stubOrganizerOnCreator();
        // auto-confirm -> event created immediately
        groupType(false);

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
        rows.forEach(r -> assertThat(r.status).isEqualTo(BookingStatus.CONFIRMED));
        // The co-host (not the creator/organizer) initiates the cancel -> keyed by ITS manageToken.
        Booking cohostRow = rows
            .stream()
            .filter(r -> !r.ownerId.equals(1L))
            .findFirst()
            .orElseThrow();
        bookingService.cancel(cohostRow.manageToken, true);

        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> assertThat(r.status).isEqualTo(BookingStatus.CANCELLED));
        verify(calendarPort, times(1)).deleteEvent(1L, null, "evt");
        assertThat(Booking.<Booking>leadOfGroup(lead.groupId, 1L).googleEventId).isNull();
    }

    // --- (a2) final-review fix: cancelling a CONFIRMED group whose organizer has since disconnected
    // Google must still succeed (all rows -> CANCELLED, local event refs cleared) without attempting
    // the remote deleteEvent call, mirroring cancelSingle's isConnected guard ---
    @Test
    @TestTransaction
    void cancelGroupWhoseOrganizerDisconnectedGoogleSucceedsWithoutCallingDeleteEvent() {
        stubOrganizerOnCreator();
        // auto-confirm -> event created immediately
        groupType(false);

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
        rows.forEach(r -> assertThat(r.status).isEqualTo(BookingStatus.CONFIRMED));
        // The organizer (creator, owner id 1) disconnects Google after confirmation.
        when(calendarPort.isConnected(1L)).thenReturn(false);

        Booking cohostRow = rows
            .stream()
            .filter(r -> !r.ownerId.equals(1L))
            .findFirst()
            .orElseThrow();
        assertDoesNotThrow(() -> bookingService.cancel(cohostRow.manageToken, true));

        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> assertThat(r.status).isEqualTo(BookingStatus.CANCELLED));
        verify(calendarPort, never()).deleteEvent(anyLong(), any(), anyString());
        assertThat(Booking.<Booking>leadOfGroup(lead.groupId, 1L).googleEventId).isNull();
    }

    // --- (b) invitee reschedule of an approval group -> all rows back to PENDING + event deleted ---
    @Test
    @TestTransaction
    void inviteeRescheduleOfApprovalGroupResetsAllToPendingAndDeletesEvent() {
        stubOrganizerOnCreator();
        groupType(true);

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
        bookingService.approve(rows.get(0).id);
        // last approval -> event created
        bookingService.approve(rows.get(1).id);
        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        // invitee-initiated (byOwner=false via the 2-arg overload)
        bookingService.reschedule(freshLead.manageToken, nextMonday(13));

        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertThat(r.status).isEqualTo(BookingStatus.PENDING);
            assertThat(r.googleEventId).isNull();
            assertThat(r.meetLink).isNull();
        });
        verify(calendarPort, times(1)).deleteEvent(anyLong(), any(), anyString());
        // no second event is created on a re-approval reschedule
        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
    }

    // --- (c) host-initiated reschedule of an approval group -> initiating host stays CONFIRMED, others PENDING ---
    @Test
    @TestTransaction
    void hostInitiatedRescheduleOfApprovalGroupKeepsInitiatorConfirmedOthersPending() {
        stubOrganizerOnCreator();
        groupType(true);

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
        bookingService.approve(rows.get(0).id);
        bookingService.approve(rows.get(1).id);
        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        Booking cohostRow =
                Booking
            .<Booking>group(lead.groupId)
            .stream()
            .filter(r -> !r.ownerId.equals(1L))
            .findFirst()
            .orElseThrow();
        long cohostId = cohostRow.ownerId;
        // The co-host reschedules from their own /me -> initiatorOwnerId = their own owner id.
        bookingService.reschedule(cohostRow.manageToken, nextMonday(13), null, true, cohostId);

        for (Booking r : Booking.<Booking>group(lead.groupId)) {
            if (r.ownerId == cohostId) {
                assertThat(r.status).as("initiating host's row stays confirmed").isEqualTo(BookingStatus.CONFIRMED);
            } else {
                assertThat(r.status).as("other hosts revert to pending").isEqualTo(BookingStatus.PENDING);
            }
        }
        verify(calendarPort, times(1)).deleteEvent(anyLong(), any(), anyString());
        assertThat(Booking.<Booking>leadOfGroup(lead.groupId, 1L).googleEventId).isNull();
    }

    // --- (d) single-host: owner reschedule of approval type stays CONFIRMED; invitee reschedule reverts ---
    @Test
    @TestTransaction
    void singleHostOwnerRescheduleOfApprovalTypeStaysConfirmed() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-s", "meet-s", "cal-s", null));
        singleHostType("solo-approval", true);

        Booking b = bookingService.book(
                1L,
                "solo-approval",
                nextMonday(10),
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of()
        );
        bookingService.approve(b.id);
        assertThat(Booking.<Booking>findById(b.id).status).isEqualTo(BookingStatus.CONFIRMED);
        // owner-initiated
        bookingService.reschedule(b.manageToken, nextMonday(13), null, true);

        Booking loaded = Booking.findById(b.id);
        assertThat(loaded.status).as("owner-initiated reschedule stays confirmed").isEqualTo(BookingStatus.CONFIRMED);
        assertThat(loaded.startUtc).isEqualTo(nextMonday(13));
        assertThat(loaded.googleEventId).as("the event is patched in place, not dropped").isNotNull();
        verify(calendarPort, times(1)).updateEvent(anyLong(), any(), eq("evt-s"), eq(nextMonday(13)), any(), any());
        verify(calendarPort, never()).deleteEvent(anyLong(), any(), anyString());
    }

    @Test
    @TestTransaction
    void singleHostInviteeRescheduleOfApprovalTypeRevertsToPending() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-s2", "meet-s2", "cal-s2", null));
        singleHostType("solo-approval-2", true);

        Booking b = bookingService.book(
                1L,
                "solo-approval-2",
                nextMonday(10),
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of()
        );
        bookingService.approve(b.id);
        assertThat(Booking.<Booking>findById(b.id).status).isEqualTo(BookingStatus.CONFIRMED);
        // invitee-initiated (2-arg overload)
        bookingService.reschedule(b.manageToken, nextMonday(13));

        Booking loaded = Booking.findById(b.id);
        assertThat(loaded.status).as("invitee-initiated reschedule reverts to pending").isEqualTo(BookingStatus.PENDING);
        assertThat(loaded.googleEventId).as("prior event is deleted on re-request").isNull();
        assertThat(loaded.meetLink).isNull();
        verify(calendarPort, times(1)).deleteEvent(anyLong(), any(), eq("evt-s2"));
        verify(calendarPort, never()).updateEvent(anyLong(), any(), any(), any(), any(), any());
    }

    // --- (e) Task 11 review fix: group reschedule to an adjacent slot must not be falsely rejected
    // by the group's OWN sibling rows, which still sit at the old time until the move loop runs ---
    @Test
    @TestTransaction
    void groupRescheduleToAdjacentSlotSucceedsDespiteSiblingRowsAtOldTime() {
        stubOrganizerOnCreator();
        // auto-confirm
        MeetingType type = groupType(false);
        // A non-zero buffer makes the new (adjacent) slot's BUFFERED interval overlap the group's own
        // OLD (unbuffered) occupied interval: [10:00,11:00) booked, buffered 11:00 slot -> [10:45,12:15).
        type.bufferBeforeMinutes = 15;
        type.bufferAfterMinutes = 15;
        type.persist();

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

        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        // Before the fix: assertSlotAvailable excluded only freshLead.id, so the co-host's OWN row
        // (still at 10:00-11:00) was counted busy against the new slot's buffered interval ->
        // BookingConflictException (409), even though the group is only shifting by one hour.
        assertDoesNotThrow(() -> bookingService.reschedule(freshLead.manageToken, nextMonday(11)));

        Booking
            .<Booking>group(lead.groupId)
            .forEach(r -> assertThat(r.startUtc).as("every group row moved to the new time").isEqualTo(nextMonday(11)));
    }

    // --- (f) Task 11 review fix: auto-confirm group reschedule deletes the old event and creates a
    // new one, moves every row, and fires BookingRescheduled (rows stay CONFIRMED, never PENDING) ---
    @Test
    @TestTransaction
    void autoConfirmGroupRescheduleDeletesOldEventAndCreatesNewOne() {
        stubOrganizerOnCreator();
        // auto-confirm
        groupType(false);

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
        rows.forEach(r -> assertThat(r.status).isEqualTo(BookingStatus.CONFIRMED));
        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());

        Booking freshLead = Booking.leadOfGroup(lead.groupId, 1L);
        var rescheduledBefore = RESCHEDULED.get();
        // invitee-initiated, far shift
        bookingService.reschedule(freshLead.manageToken, nextMonday(15));

        Booking.<Booking>group(lead.groupId).forEach(r -> {
            assertThat(r.status).as("auto-confirm group reschedule stays confirmed").isEqualTo(BookingStatus.CONFIRMED);
            assertThat(r.startUtc).isEqualTo(nextMonday(15));
        });
        verify(calendarPort, times(1)).deleteEvent(1L, null, "evt");
        verify(calendarPort, times(2))
            .createEvent(anyLong(), any(), any(), any(), any(), any(), anyList(), anyBoolean(), any());
        assertThat(RESCHEDULED.get()).as("BookingRescheduled fired once").isEqualTo(rescheduledBefore + 1);
    }
}
