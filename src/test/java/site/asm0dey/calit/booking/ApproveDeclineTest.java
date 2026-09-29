package site.asm0dey.calit.booking;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.availability.TimeSlot;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

@QuarkusTest
class ApproveDeclineTest {
    @Inject
    BookingService bookingService;
    @InjectMock
    CalendarPort calendarPort;
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    // 09:00 local
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();

    @Test
    @TestTransaction
    void approveFlipsToConfirmedAndCreatesEvent() {
        // Feature 14: approve a PENDING request -> CONFIRMED + Google event created now.
        seedSettings();
        approvalType("approve");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                anyLong(),
                any(),
                anyString(),
                anyString(),
                eq(SLOT_09),
                any(),
                any(),
                anyBoolean(),
                any()
        ))
            .thenReturn(new CreatedEvent("evt-ap", "https://meet.google.com/ap-1-2", "h", null));

        Booking b =
                bookingService.book(
                        1L,
                        "approve",
                        SLOT_09,
                        "Sam",
                        "sam@example.com",
                        Map.of(),
                        "tok",
                        "",
                        "en",
                        List.of()
        );
        assertEquals(BookingStatus.PENDING, b.status);

        bookingService.approve(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        assertEquals("evt-ap", loaded.googleEventId);
        assertEquals("https://meet.google.com/ap-1-2", loaded.meetLink);
        // The event is created at approve time (createMeetLink=true for GOOGLE_MEET), not at book time.
        verify(calendarPort, times(1))
            .createEvent(
                    anyLong(),
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
    @TestTransaction
    void declineFlipsToDeclinedFreesSlotAndCreatesNoEvent() {
        // Feature 14: decline a PENDING request -> DECLINED, slot freed, no Google event.
        seedSettings();
        MeetingType t = approvalType("decline");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        Booking b =
                bookingService.book(
                        1L,
                        "decline",
                        SLOT_09,
                        "Sam",
                        "sam@example.com",
                        Map.of(),
                        "tok",
                        "",
                        "en",
                        List.of()
        );
        // While PENDING, the 09:00 slot is held.
        assertTrue(bookingService
            .availableSlots(t, DAY, DAY)
            .stream()
            .noneMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0)))
        );

        bookingService.decline(b.id);

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.DECLINED, loaded.status);
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        // DECLINED leaves the partial constraint -> 09:00 is bookable again.
        List<TimeSlot> avail = bookingService.availableSlots(t, DAY, DAY);
        assertTrue(avail
            .stream()
            .anyMatch(s -> s.start().toLocalTime().equals(LocalTime.of(9, 0))));
    }

    @Test
    @TestTransaction
    void approveOnCancelledBookingIsNoOp() {
        // calit-wsab: a stale /me/pending tab must not resurrect a cancelled request.
        seedSettings();
        approvalType("ap-cancelled");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("ap-cancelled");
        bookingService.cancel(b.manageToken);

        bookingService.approve(b.id);

        assertEquals(BookingStatus.CANCELLED, Booking.<Booking>findById(b.id).status);
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void approveOnDeclinedBookingIsNoOp() {
        seedSettings();
        approvalType("ap-declined");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("ap-declined");
        bookingService.decline(b.id);

        bookingService.approve(b.id);

        assertEquals(BookingStatus.DECLINED, Booking.<Booking>findById(b.id).status);
        verify(calendarPort, never())
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void approveOnConfirmedBookingIsNoOp() {
        // Double-click on Approve: the second click must not create a second Google event.
        seedSettings();
        approvalType("ap-twice");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                anyLong(),
                any(),
                anyString(),
                anyString(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        ))
            .thenReturn(new CreatedEvent("evt-once", "https://meet.google.com/x", "h", null));
        Booking b = pendingBooking("ap-twice");
        bookingService.approve(b.id);

        bookingService.approve(b.id);

        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @TestTransaction
    void declineOnConfirmedBookingIsNoOp() {
        // A confirmed meeting is cancelled (Google event deleted, cancel email), never "declined".
        seedSettings();
        approvalType("de-confirmed");
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("de-confirmed");
        bookingService.approve(b.id);

        bookingService.decline(b.id);

        assertEquals(BookingStatus.CONFIRMED, Booking.<Booking>findById(b.id).status);
    }

    @Test
    @TestTransaction
    void declineOnCancelledBookingIsNoOp() {
        seedSettings();
        approvalType("de-cancelled");
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Booking b = pendingBooking("de-cancelled");
        bookingService.cancel(b.manageToken);

        bookingService.decline(b.id);

        assertEquals(BookingStatus.CANCELLED, Booking.<Booking>findById(b.id).status);
    }

    @Test
    void concurrentApproveCreatesOneEvent() throws Exception {
        // Two Approve clicks that overlap: the second must wait for the first to commit, then see CONFIRMED.
        QuarkusTransaction.requiringNew().run(() -> {
            seedSettings();
            approvalType("ap-race");
        });
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                anyLong(),
                any(),
                anyString(),
                anyString(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        ))
            .thenAnswer(inv -> {
                // a slow Google call keeps the first transaction open while the second one reads
                Thread.sleep(500);
                return new CreatedEvent("evt-race", "https://meet.google.com/r", "h", null);
            });
        Booking b = pendingBooking("ap-race");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                bookingService.approve(b.id);
                return null;
            });
            var second = pool.submit(() -> {
                start.await();
                bookingService.approve(b.id);
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        verify(calendarPort, times(1))
            .createEvent(anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
    }

    private Booking pendingBooking(String slug) {
        Booking b =
                bookingService.book(1L, slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());
        assertEquals(BookingStatus.PENDING, b.status);
        return b;
    }

    // --- helpers ---
    private void seedSettings() {
        // Idempotent upsert: a non-@TestTransaction REST test (MeetingTypeResourceTest PUT /api/settings)
        // may have committed the singleton row before this suite runs, so reuse it if present rather
        // than re-inserting the same primary key (which would violate owner_settings_pkey).
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    private MeetingType approvalType(String slug) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.GOOGLE_MEET;
        // feature 14
        t.requiresApproval = true;
        t.persist();
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = DAY.getDayOfWeek();
        r.startTime = LocalTime.of(9, 0);
        r.endTime = LocalTime.of(11, 0);
        r.meetingTypeId = null;
        r.persist();
        return t;
    }
}
