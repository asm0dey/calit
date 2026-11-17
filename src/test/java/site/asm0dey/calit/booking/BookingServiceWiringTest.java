package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.calendar.CalendarRef;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.calendar.WriteTargetResolver;
import site.asm0dey.calit.domain.BookingFieldRepository;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.MeetingTypeDurationRepository;
import site.asm0dey.calit.domain.MeetingTypeHostRepository;
import site.asm0dey.calit.domain.MeetingTypeRepository;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.domain.OwnerSettingsRepository;

/**
 * {@link BookingService} wiring over mocked repositories and ports: no database (#343).
 */
@SuppressWarnings("unchecked")
class BookingServiceWiringTest {
    private static final Long OWNER = 1L;
    private static final Long TYPE_ID = 7L;
    private static final Long BOOKING_ID = 42L;
    private static final Instant SLOT_09 = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    private static final Instant SLOT_10 = SLOT_09.plusSeconds(3600);
    private final SlotService slotService = mock(SlotService.class);
    private final CalendarPort calendarPort = mock(CalendarPort.class);
    private final MeetingHosts meetingHosts = mock(MeetingHosts.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final MeetingTypeRepository meetingTypes = mock(MeetingTypeRepository.class);
    private final OwnerSettingsRepository ownerSettings = mock(OwnerSettingsRepository.class);
    private final BookingService bookingService = new BookingService(
            slotService,
            calendarPort,
            mock(CaptchaVerifier.class),
            meetingHosts,
            mock(WriteTargetResolver.class),
            10,
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            bookings,
            mock(BookingGuestRepository.class),
            mock(BookingInviteRepository.class),
            meetingTypes,
            mock(MeetingTypeHostRepository.class),
            mock(MeetingTypeDurationRepository.class),
            ownerSettings,
            mock(BookingFieldRepository.class)
    );

    /**
     * A one-hour Google Meet type on {@link #OWNER}, served by {@code findById}, with the owner's settings.
     */
    private MeetingType type(boolean requiresApproval) {
        var t = new MeetingType();
        t.id = TYPE_ID;
        t.ownerId = OWNER;
        t.name = "approve";
        t.slug = "approve";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = requiresApproval;
        when(meetingTypes.findById(TYPE_ID)).thenReturn(t);
        var s = new OwnerSettings();
        s.ownerId = OWNER;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        when(ownerSettings.forOwner(OWNER)).thenReturn(s);
        return t;
    }

    private static Booking booking(BookingStatus status) {
        var b = new Booking();
        b.id = BOOKING_ID;
        b.ownerId = OWNER;
        b.meetingTypeId = TYPE_ID;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = SLOT_09;
        b.endUtc = SLOT_09.plusSeconds(3600);
        b.manageToken = "manage";
        b.status = status;
        return b;
    }

    /**
     * A PENDING 09:00 request on an approval type, served by {@code lockForDecision}.
     */
    private Booking pendingBooking() {
        type(true);
        var b = booking(BookingStatus.PENDING);
        b.approvalToken = "approval";
        when(bookings.lockForDecision(BOOKING_ID)).thenReturn(b);
        return b;
    }

    /**
     * A CONFIRMED 09:00 booking whose Mirror event {@code eventId} lives at {@code ref}, served by its manage token.
     */
    private Booking confirmedWithEvent(boolean requiresApproval, CalendarRef ref, String eventId) {
        type(requiresApproval);
        var b = booking(BookingStatus.CONFIRMED);
        b.eventId = eventId;
        if (ref != null) {
            b.calendarAccountId = ref.accountId();
            b.calendarRemoteId = ref.remoteCalendarId();
        }
        when(bookings.findByManageToken(b.manageToken)).thenReturn(b);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        return b;
    }

    /**
     * The single host may take a one-hour booking at {@code slot}.
     */
    private void slotFree(Instant slot) {
        var t = meetingTypes.findById(TYPE_ID);
        when(meetingHosts.bookable(t)).thenReturn(true);
        when(meetingHosts.hostOwnerIds(t)).thenReturn(List.of(OWNER));
        var start = slot.atZone(ZoneOffset.UTC);
        when(slotService.generateRawSlots(eq(t), eq(OWNER), any(), any(), any(), eq(60)))
            .thenReturn(List.of(new TimeSlot(start, start.plusHours(1))));
    }

    private void createEventReturns(CreatedEvent created) {
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
            .thenReturn(created);
    }

    private void verifyNoEventCreated() {
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void approveFlipsToConfirmedAndCreatesEvent() {
        // Feature 14: approve a PENDING request -> CONFIRMED + Google event created now.
        Booking loaded = pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-ap", "https://meet.google.com/ap-1-2", "h", null));

        bookingService.approve(BOOKING_ID);

        assertThat(loaded.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(loaded.eventId).isEqualTo("evt-ap");
        assertThat(loaded.meetLink).isEqualTo("https://meet.google.com/ap-1-2");
        // The event is created at approve time (createMeetLink=true for GOOGLE_MEET), not at book time.
        verify(calendarPort, times(1))
            .createEvent(
                    anyLong(),
                    any(),
                    any(),
                    anyString(),
                    anyString(),
                    eq(SLOT_09),
                    eq(SLOT_09.plusSeconds(3600)),
                    eq(List.of("sam@example.com", "owner@example.com")),
                    eq(true),
                    eq(null)
            );
    }

    @Test
    void approveOnCancelledBookingIsNoOp() {
        // calit-wsab: a stale /me/pending tab must not resurrect a cancelled request.
        Booking b = pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        b.status = BookingStatus.CANCELLED;

        bookingService.approve(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.CANCELLED);
        verifyNoEventCreated();
    }

    @Test
    void approveOnDeclinedBookingIsNoOp() {
        Booking b = pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        bookingService.decline(BOOKING_ID);

        bookingService.approve(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.DECLINED);
        verifyNoEventCreated();
    }

    @Test
    void approveOnConfirmedBookingIsNoOp() {
        // Double-click on Approve: the second click must not create a second Google event.
        pendingBooking();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-once", "https://meet.google.com/x", "h", null));
        bookingService.approve(BOOKING_ID);

        bookingService.approve(BOOKING_ID);

        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void declineOnConfirmedBookingIsNoOp() {
        // A confirmed meeting is cancelled (Google event deleted, cancel email), never "declined".
        Booking b = pendingBooking();
        bookingService.approve(BOOKING_ID);

        bookingService.decline(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void declineOnCancelledBookingIsNoOp() {
        Booking b = pendingBooking();
        b.status = BookingStatus.CANCELLED;

        bookingService.decline(BOOKING_ID);

        assertThat(b.status).isEqualTo(BookingStatus.CANCELLED);
    }

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
}
