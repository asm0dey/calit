package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.calendar.CalendarPort;
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
    private final CalendarPort calendarPort = mock(CalendarPort.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final MeetingTypeRepository meetingTypes = mock(MeetingTypeRepository.class);
    private final OwnerSettingsRepository ownerSettings = mock(OwnerSettingsRepository.class);
    private final BookingService bookingService = new BookingService(
            mock(SlotService.class),
            calendarPort,
            mock(CaptchaVerifier.class),
            mock(MeetingHosts.class),
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
     * A one-hour Google Meet approval type with a PENDING 09:00 request, served by {@code lockForDecision}.
     */
    private Booking pendingBooking() {
        var t = new MeetingType();
        t.id = TYPE_ID;
        t.ownerId = OWNER;
        t.name = "approve";
        t.slug = "approve";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = true;
        when(meetingTypes.findById(TYPE_ID)).thenReturn(t);
        var s = new OwnerSettings();
        s.ownerId = OWNER;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        when(ownerSettings.forOwner(OWNER)).thenReturn(s);
        var b = new Booking();
        b.id = BOOKING_ID;
        b.ownerId = OWNER;
        b.meetingTypeId = TYPE_ID;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = SLOT_09;
        b.endUtc = SLOT_09.plusSeconds(3600);
        b.manageToken = "manage";
        b.approvalToken = "approval";
        b.status = BookingStatus.PENDING;
        when(bookings.lockForDecision(BOOKING_ID)).thenReturn(b);
        return b;
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
}
