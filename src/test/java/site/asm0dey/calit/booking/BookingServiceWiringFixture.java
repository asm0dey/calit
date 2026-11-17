package site.asm0dey.calit.booking;

import module java.base;
import static org.mockito.Mockito.*;
import jakarta.enterprise.event.Event;
import site.asm0dey.calit.availability.SlotService;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.calendar.CalendarAccountRepository;
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
import site.asm0dey.calit.i18n.AppLocaleDiscovery;

/**
 * {@link BookingService} built with {@code new} over mocked repositories and ports: no database (#343). The
 * {@code *WiringTest} classes extend it, one per feature.
 *
 * <p>{@code persistAndFlush} behaves as the persistence context would inside one transaction: the row gets an id and
 * is found again, as the same object, by its manage token and id.
 */
@SuppressWarnings("unchecked")
abstract class BookingServiceWiringFixture {
    static final Long OWNER = 1L;
    static final Long TYPE_ID = 7L;
    static final Long BOOKING_ID = 42L;
    static final Instant SLOT_09 = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    static final Instant SLOT_10 = SLOT_09.plusSeconds(3600);
    final SlotService slotService = mock(SlotService.class);
    final CalendarPort calendarPort = mock(CalendarPort.class);
    final MeetingHosts meetingHosts = mock(MeetingHosts.class);
    final WriteTargetResolver writeTargets = mock(WriteTargetResolver.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingGuestRepository guests = mock(BookingGuestRepository.class);
    final MeetingTypeRepository meetingTypes = mock(MeetingTypeRepository.class);
    final MeetingTypeDurationRepository durations = mock(MeetingTypeDurationRepository.class);
    final OwnerSettingsRepository ownerSettings = mock(OwnerSettingsRepository.class);
    final AppLocaleDiscovery appLocaleDiscovery = mock(AppLocaleDiscovery.class);
    final Event<BookingRequested> requested = mock(Event.class);
    final Event<BookingConfirmed> confirmed = mock(Event.class);
    final Event<BookingRescheduled> rescheduled = mock(Event.class);
    final Event<BookingCancelled> cancelled = mock(Event.class);
    final BookingService bookingService = new BookingService(
            slotService,
            calendarPort,
            mock(CaptchaVerifier.class),
            meetingHosts,
            writeTargets,
            10,
            requested,
            confirmed,
            mock(Event.class),
            mock(Event.class),
            rescheduled,
            cancelled,
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            mock(Event.class),
            bookings,
            guests,
            mock(BookingInviteRepository.class),
            meetingTypes,
            mock(MeetingTypeHostRepository.class),
            durations,
            ownerSettings,
            mock(BookingFieldRepository.class),
            mock(CalendarAccountRepository.class),
            appLocaleDiscovery
    );
    /**
     * The row {@code persistAndFlush} last wrote.
     */
    // shortcut: holds one stored booking per test; a test that books two rows must stub its own lookups
    private Booking stored;

    BookingServiceWiringFixture() {
        doAnswer(inv -> {
            Booking b = inv.getArgument(0);
            if (b.id == null) {
                b.id = BOOKING_ID;
            }
            stored = b;
            return null;
        }).when(bookings).persistAndFlush(any(Booking.class));
        when(bookings.findByManageToken(anyString()))
            .thenAnswer(inv -> stored != null && stored.manageToken.equals(inv.getArgument(0)) ? stored : null);
        when(bookings.lockForDecision(anyLong())).thenAnswer(inv -> stored(inv.getArgument(0)));
        when(bookings.findById(anyLong())).thenAnswer(inv -> stored(inv.getArgument(0)));
        when(durations.isAllowed(any(), anyInt())).thenReturn(true);
    }

    private Booking stored(Long id) {
        return stored != null && stored.id.equals(id) ? stored : null;
    }

    /**
     * A one-hour Google Meet type on {@link #OWNER}, served by {@code findById} and {@code findBySlug}, with the
     * owner's settings.
     */
    MeetingType type(boolean requiresApproval) {
        var t = new MeetingType();
        t.id = TYPE_ID;
        t.ownerId = OWNER;
        t.name = "approve";
        t.slug = "approve";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.requiresApproval = requiresApproval;
        when(meetingTypes.findById(TYPE_ID)).thenReturn(t);
        when(meetingTypes.findBySlug(OWNER, t.slug)).thenReturn(t);
        var s = new OwnerSettings();
        s.ownerId = OWNER;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        when(ownerSettings.forOwner(OWNER)).thenReturn(s);
        return t;
    }

    static Booking booking(BookingStatus status) {
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
    Booking pendingBooking() {
        type(true);
        var b = booking(BookingStatus.PENDING);
        b.approvalToken = "approval";
        when(bookings.lockForDecision(BOOKING_ID)).thenReturn(b);
        return b;
    }

    /**
     * A CONFIRMED 09:00 booking whose Mirror event {@code eventId} lives at {@code ref}, served by its manage token.
     */
    Booking confirmedWithEvent(boolean requiresApproval, CalendarRef ref, String eventId) {
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
    void slotFree(Instant slot) {
        slotsFree(60, slot);
    }

    /**
     * The single host may take a {@code minutes}-long booking at each of {@code slots}, on whichever day is asked.
     */
    void slotsFree(int minutes, Instant... slots) {
        var t = meetingTypes.findById(TYPE_ID);
        when(meetingHosts.bookable(t)).thenReturn(true);
        when(meetingHosts.hostOwnerIds(t)).thenReturn(List.of(OWNER));
        var free = Arrays
            .stream(slots)
            .map(s -> s.atZone(ZoneOffset.UTC))
            .map(s -> new TimeSlot(s, s.plusMinutes(minutes)))
            .toList();
        when(slotService.generateRawSlots(eq(t), eq(OWNER), any(), any(), any(), eq(minutes))).thenReturn(free);
    }

    /**
     * Sam books {@code t} at 09:00.
     */
    Booking book(MeetingType t) {
        return bookingService.book(
                OWNER,
                t.slug,
                SLOT_09,
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of()
        );
    }

    void createEventReturns(CreatedEvent created) {
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

    void verifyNoEventCreated() {
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }
}
