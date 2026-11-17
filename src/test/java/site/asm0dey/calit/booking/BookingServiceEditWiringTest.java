package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CalendarRef;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.calendar.EventGoneException;
import site.asm0dey.calit.domain.MeetingType;

/**
 * {@link BookingService} reschedule/updateDetails wiring over mocked repositories and ports: no database (#343).
 */
class BookingServiceEditWiringTest extends BookingServiceWiringFixture {
    @Test
    void rescheduleOfANonApprovalTypePatchesTheStoredCalendar() {
        // The auto-confirm path patches the existing event via updateEvent instead of deleting it, and must
        // address that patch at the booking's *stored* ref, not the owner's current write target.
        CalendarRef ref = new CalendarRef(5L, "work@example.com");
        Booking booked = confirmedWithEvent(false, ref, "evt-resched-auto");
        slotFree(SLOT_10);

        bookingService.reschedule(booked.manageToken, SLOT_10);

        verify(calendarPort).updateEvent(eq(booked.ownerId), eq(ref), eq("evt-resched-auto"), any(), any(), any());
    }

    @Test
    void updateDetailsPatchesTheStoredCalendar() {
        CalendarRef ref = new CalendarRef(5L, "work@example.com");
        Booking booked = confirmedWithEvent(false, ref, "evt-details");

        bookingService.updateDetails(booked.manageToken, "New title", "New description", null, false);

        verify(calendarPort).updateEventDetails(eq(booked.ownerId), eq(ref), eq("evt-details"), any(), any(), any());
    }

    @Test
    void updateDetailsRejectsOverlongTitle() {
        Booking b = confirmedWithEvent(false, null, null);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var title = "x".repeat(201);
        List<String> guestEmails = List.of();
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> bookingService.updateDetails(b.manageToken, title, null, guestEmails, true));
    }

    @Test
    void rescheduleApprovalTypeReturnsToPendingAndDeletesEvent() {
        // Feature 14: rescheduling an approval-type booking re-enters the approval queue (PENDING),
        // deletes any existing Google event, and fires a re-request (not updateEvent).
        MeetingType t = type(true);
        slotsFree(60, SLOT_09, SLOT_10);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        // Book PENDING, then approve so it has a CONFIRMED Google event to delete on reschedule.
        createEventReturns(new CreatedEvent("evt-ra", "https://meet.google.com/ra-1-2", "h", null));
        Booking b = book(t);
        bookingService.approve(b.id);

        bookingService.reschedule(b.manageToken, SLOT_10);

        Booking loaded = b;
        assertThat(loaded.status).as("approval type re-enters PENDING on reschedule").isEqualTo(BookingStatus.PENDING);
        assertThat(loaded.startUtc).isEqualTo(SLOT_10);
        assertThat(loaded.eventId).as("the prior event is deleted on re-request").isNull();
        assertThat(loaded.meetLink).isNull();
        verify(calendarPort, times(1)).deleteEvent(anyLong(), any(), eq("evt-ra"));
        verify(calendarPort, never()).updateEvent(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void inviteeRescheduleToPendingStampsPendingSince() {
        // #309: the hold restarts when approval is re-requested, not at the original creation
        MeetingType t = type(true);
        slotsFree(60, SLOT_09, SLOT_10);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Booking b = book(t);
        bookingService.approve(b.id);
        b.createdAt = Instant.now().minus(48, ChronoUnit.HOURS);

        bookingService.reschedule(b.manageToken, SLOT_10);

        assertThat(b.status).isEqualTo(BookingStatus.PENDING);
        assertThat(b.pendingSince).isAfter(Instant.now().minusSeconds(60));
    }

    private static final CalendarRef STORED = new CalendarRef(null, "stored@example.com");

    /**
     * #283: an auto-confirmed booking whose Mirror event ("evt-1") the owner then deleted by hand in their calendar; the
     * next create returns {@code recreated}.
     */
    private Booking handDeletedMirror(CreatedEvent recreated) {
        MeetingType t = type(false);
        slotsFree(60, SLOT_09, SLOT_10);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.createEvent(
                anyLong(),
                any(),
                any(),
                anyString(),
                anyString(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        ))
            .thenReturn(new CreatedEvent("evt-1", null, "h", STORED), recreated);
        Booking b = book(t);
        doThrow(new EventGoneException("evt-1", null))
            .when(calendarPort)
            .updateEvent(anyLong(), any(), eq("evt-1"), any(), any(), any());
        doThrow(new EventGoneException("evt-1", null))
            .when(calendarPort)
            .updateEventDetails(anyLong(), any(), eq("evt-1"), any(), any(), any());
        return b;
    }

    private void verifyCreatedTwice() {
        verify(calendarPort, times(2))
            .createEvent(anyLong(), any(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void rescheduleOfHandDeletedEventRecreatesAndRestamps() {
        Booking b = handDeletedMirror(new CreatedEvent("evt-2", null, "h", STORED));

        bookingService.reschedule(b.manageToken, SLOT_10, null, true);

        assertThat(b.startUtc).isEqualTo(SLOT_10);
        assertThat(b.eventId).isEqualTo("evt-2");
        verifyCreatedTwice();
        verify(rescheduled, times(1).description("the invitee is still told")).fire(any());
    }

    @Test
    void inviteeRescheduleOfHandDeletedEventAlsoRecreates() {
        Booking b = handDeletedMirror(new CreatedEvent("evt-2", null, "h", STORED));

        bookingService.reschedule(b.manageToken, SLOT_10);

        assertThat(b.eventId).isEqualTo("evt-2");
        verifyCreatedTwice();
    }

    @Test
    void detailsEditOfHandDeletedEventRecreates() {
        Booking b = handDeletedMirror(new CreatedEvent("evt-2", null, "h", STORED));

        bookingService.updateDetails(b.manageToken, "T", "D", List.of(), true);

        assertThat(b.eventId).isEqualTo("evt-2");
        verifyCreatedTwice();
    }

    @Test
    void recreateWithNoWriteCalendarLeavesNoAddress() {
        // ADR 0013: no write calendar means no Mirror event, not an error
        Booking b = handDeletedMirror(null);

        assertThatCode(() -> bookingService.reschedule(b.manageToken, SLOT_10, null, true)).doesNotThrowAnyException();

        assertThat(b.eventId).isNull();
        assertThat(b.calendarRemoteId).isNull();
        assertThat(b.calendarAccountId).isNull();
    }

    @Test
    void rescheduleToSameTimeIsNoOp() {
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-nn", "https://meet.google.com/n-n-n", "h", null));

        Booking b = book(t);
        int beforeSeq = b.icsSequence;
        clearInvocations(calendarPort);
        // Reschedule to the SAME start with guests untouched (null) -> no-op.
        bookingService.reschedule(b.manageToken, SLOT_09, null, false);

        assertThat(b.startUtc).isEqualTo(SLOT_09);
        assertThat(b.icsSequence).as("no-op must not bump the sequence").isEqualTo(beforeSeq);
        verify(calendarPort, never()).updateEvent(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void bookingRecordsTheCalendarTheEventWasCreatedOn() {
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-created", null, null, new CalendarRef(5L, "work@example.com")));

        Booking booked = book(t);

        assertThat(booked.calendarRemoteId).isEqualTo("work@example.com");
        assertThat(booked.calendarAccountId).isEqualTo(5L);
    }

    @Test
    void rescheduleOfAnApprovalTypeDeletesOnTheStoredCalendar() {
        // Task 4 Step 4: an invitee-initiated reschedule of an approval type re-enters PENDING and
        // deletes the prior Google event. `priorRef` in BookingService.reschedule/applyRescheduleOutcome
        // must be captured BEFORE the re-approval block clears googleCalendarId/googleCredentialId --
        // otherwise the delete would address the wrong (cleared/null) ref.
        CalendarRef ref = new CalendarRef(5L, "work@example.com");
        MeetingType t = type(true);
        slotsFree(60, SLOT_09, SLOT_10);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-resched", null, null, ref));
        Booking booked = book(t);
        bookingService.approve(booked.id);
        // Invitee-initiated (byOwner defaults false) -> triggers re-approval.
        bookingService.reschedule(booked.manageToken, SLOT_10);

        verify(calendarPort).deleteEvent(booked.ownerId, ref, "evt-resched");

        assertThat(booked.status).isEqualTo(BookingStatus.PENDING);
        assertThat(booked.eventId).isNull();
        assertThat(booked.calendarRemoteId).isNull();
        assertThat(booked.calendarAccountId).isNull();
    }
}
