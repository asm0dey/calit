package site.asm0dey.calit.booking;

import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.booking.events.BookingRequested;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class BookServiceTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    // CDI observers count fired events (feature 14 / degraded mode assertions).
    static final AtomicInteger REQUESTED = new AtomicInteger();
    static final AtomicInteger CONFIRMED = new AtomicInteger();

    void onRequested(@Observes BookingRequested e) { REQUESTED.incrementAndGet(); }
    void onConfirmed(@Observes BookingConfirmed e) { CONFIRMED.incrementAndGet(); }

    // Owner tz Europe/Amsterdam. Derive a future weekday from now() so the slot is never in the past.
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant(); // 09:00 local

    @Test
    @TestTransaction
    void happyPathPersistsBookingWithMeetLinkAndFiresEvent() {
        seedSettings();
        meetingTypeWithMondayWindow("book-happy", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), eq(SLOT_09), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-99", "https://meet.google.com/xyz-1234-pqr",
                        "https://calendar.google.com/evt-99"));

        // No per-type fields and the only global field (seeded description) is optional,
        // so an empty answers map books successfully.
        Booking b = bookingService.book(1L, "book-happy", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en");

        assertEquals(BookingStatus.CONFIRMED, b.status);
        assertEquals("evt-99", b.googleEventId);
        assertEquals("https://meet.google.com/xyz-1234-pqr", b.meetLink);
        Booking loaded = Booking.findById(b.id);
        assertEquals("https://meet.google.com/xyz-1234-pqr", loaded.meetLink);
        // Owner email is included as an attendee; createMeetLink=true for GOOGLE_MEET; null locationText.
        verify(calendarPort, times(1)).createEvent(anyLong(), anyString(), anyString(), eq(SLOT_09),
                eq(SLOT_09.plusSeconds(3600)), eq(List.of("sam@example.com", "owner@example.com")),
                eq(true), eq(null));
    }

    @Test
    @TestTransaction
    void autoTypeConnectedNonMeetLocationPassesLocationTextAndNoMeetLink() {
        // Feature 13: PHONE location -> createMeetLink=false, locationText=locationDetail.
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-phone", LocationType.PHONE, false);
        t.locationDetail = "+31 20 123 4567";
        t.persist();
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ph", null, "h"));

        Booking b = bookingService.book(1L, "book-phone", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en");

        assertEquals(BookingStatus.CONFIRMED, b.status);
        assertNull(b.meetLink, "no Meet link for a non-Meet location");
        verify(calendarPort, times(1)).createEvent(anyLong(), anyString(), anyString(), any(), any(), any(),
                eq(false), eq("+31 20 123 4567"));
    }

    @Test
    @TestTransaction
    void autoTypeDisconnectedConfirmsWithoutEventAndNullMeetLink() {
        // Degraded mode (feature 2 optional): not connected -> no Google event, null googleEventId/meetLink.
        seedSettings();
        meetingTypeWithMondayWindow("book-degraded", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        Booking b = bookingService.book(1L, "book-degraded", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en");

        assertEquals(BookingStatus.CONFIRMED, b.status);
        assertNull(b.googleEventId);
        assertNull(b.meetLink);
        // createEvent and freeBusy must never be called when disconnected.
        verify(calendarPort, never()).createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        verify(calendarPort, never()).freeBusy(anyLong(), any(), any());
    }

    @Test
    @TestTransaction
    void approvalTypeCreatesPendingWithoutEventAndFiresRequested() {
        // Feature 14: requiresApproval -> PENDING hold, NO Google event, BookingRequested fired.
        seedSettings();
        meetingTypeWithMondayWindow("book-approval", LocationType.GOOGLE_MEET, true);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        int requestedBefore = REQUESTED.get();
        int confirmedBefore = CONFIRMED.get();

        Booking b = bookingService.book(1L, "book-approval", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en");

        assertEquals(BookingStatus.PENDING, b.status);
        assertNull(b.googleEventId);
        assertNull(b.meetLink);
        // The PENDING request must NOT touch Google.
        verify(calendarPort, never()).createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any());
        assertEquals(requestedBefore + 1, REQUESTED.get(), "BookingRequested fired for approval type");
        assertEquals(confirmedBefore, CONFIRMED.get(), "BookingConfirmed NOT fired for a PENDING request");
    }

    @Test
    @TestTransaction
    void optionalDescriptionMayBeOmitted() {
        // The seeded global `description` field (feature 10 default) is optional,
        // so a booking that omits it still succeeds (regression guard for required-loop logic).
        seedSettings();
        meetingTypeWithMondayWindow("book-optional", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-opt", "https://meet.google.com/opt-1-2", "h"));

        Booking b = bookingService.book(1L, "book-optional", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en");

        assertEquals(BookingStatus.CONFIRMED, b.status);
    }

    @Test
    @TestTransaction
    void requiredCustomFieldMissingThrowsValidation() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-missing", LocationType.GOOGLE_MEET, false);
        // Per-type required field: formFor(t.id) now returns this override (not the global form).
        requiredField(t.id, "company", "Company");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        // answers lacks "company" -> 422-mapped validation failure, before any Google call.
        assertThrows(BookingValidationException.class, () ->
                bookingService.book(1L, "book-required-missing", SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en"));
    }

    @Test
    @TestTransaction
    void requiredCustomFieldBlankThrowsValidation() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-blank", LocationType.GOOGLE_MEET, false);
        requiredField(t.id, "company", "Company");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        // Present but blank value is rejected just like a missing key.
        assertThrows(BookingValidationException.class, () ->
                bookingService.book(1L, "book-required-blank", SLOT_09, "Sam", "sam@example.com",
                        Map.of("company", "   "), "tok", "", "en"));
    }

    @Test
    @TestTransaction
    void requiredCustomFieldPresentPersistsAndStoresAnswers() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-ok", LocationType.GOOGLE_MEET, false);
        requiredField(t.id, "company", "Company");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ans", "https://meet.google.com/ans-1-2", "h"));

        Booking b = bookingService.book(1L, "book-required-ok", SLOT_09, "Sam", "sam@example.com",
                Map.of("company", "Acme", "note", "extra-key-kept"), "tok", "", "en");

        Booking loaded = Booking.findById(b.id);
        assertEquals(BookingStatus.CONFIRMED, loaded.status);
        // The submitted answers (including the unknown extra key) are stored verbatim.
        assertEquals("Acme", loaded.answers.get("company"));
        assertEquals("extra-key-kept", loaded.answers.get("note"));
    }

    @Test
    @TestTransaction
    void doubleBookOnSameSlotThrowsConflict() {
        seedSettings();
        meetingTypeWithMondayWindow("book-double", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-1", "https://meet.google.com/a-b-c", "h"));

        bookingService.book(1L, "book-double", SLOT_09, "First", "first@example.com", Map.of(), "tok", "", "en");

        // Second attempt on the now-taken slot is rejected (the persisted booking is busy).
        // The app-level re-check catches it here; the DB exclusion constraint is the
        // cross-replica backstop (guards against concurrent inserts from multiple replicas).
        assertThrows(BookingConflictException.class,
                () -> bookingService.book(1L, "book-double", SLOT_09, "Second", "second@example.com", Map.of(), "tok", "", "en"));
    }

    @Test
    @TestTransaction
    void perEmailDailyCapExceededThrowsRateLimit() {
        // Feature 16: the same email's bookings created today are counted; over the cap -> 429.
        // Default cap is 10 (application.properties). Persist 10 prior bookings created "now"
        // for this email, then the 11th book() is rejected before persisting.
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-cap", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        for (int i = 0; i < 10; i++) {
            Booking prior = new Booking();
            prior.ownerId = 1L;
            prior.meetingTypeId = t.id;
            prior.inviteeName = "Spammer";
            prior.inviteeEmail = "spam@example.com";
            prior.startUtc = SLOT_09.plusSeconds(3600L * (i + 5));
            prior.endUtc = prior.startUtc.plusSeconds(3600);
            prior.status = BookingStatus.CANCELLED; // status irrelevant to the per-email count
            prior.createdAt = Instant.now();
            prior.manageToken = java.util.UUID.randomUUID().toString();
            prior.persist();
        }

        assertThrows(RateLimitException.class, () ->
                bookingService.book(1L, "book-cap", SLOT_09, "Spammer", "spam@example.com", Map.of(), "tok", "", "en"));
    }

    @Test
    @TestTransaction
    void filledHoneypotThrowsAbuse() {
        // Feature 16: a non-empty honeypot means a bot filled the hidden "website" field.
        // It is rejected INSIDE book() with AbuseException (HTTP 400), like a failed Turnstile,
        // before any booking is persisted. The honeypot guard is independent of the Turnstile flag.
        seedSettings();
        meetingTypeWithMondayWindow("book-honeypot", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        assertThrows(AbuseException.class, () ->
                bookingService.book(1L, "book-honeypot", SLOT_09, "Bot", "bot@example.com",
                        Map.of(), "tok", "http://spam.example", "en"));
    }

    @Test
    @TestTransaction
    void bookingAtUnavailableStartThrowsConflict() {
        seedSettings();
        meetingTypeWithMondayWindow("book-bad-start", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());

        // 09:13 is not a generated slot start.
        assertThrows(BookingConflictException.class, () ->
                bookingService.book(1L, "book-bad-start",
                        DAY.atTime(9, 13).atZone(ZONE).toInstant(), "X", "x@example.com", Map.of(), "tok", "", "en"));
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

    private MeetingType meetingTypeWithMondayWindow(String slug, LocationType location, boolean requiresApproval) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000; // keep the DAY slot inside the horizon regardless of run date
        t.locationType = location;
        t.requiresApproval = requiresApproval;
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

    /** Adds a required per-type custom field so formFor(typeId) returns this override. */
    private void requiredField(Long typeId, String key, String label) {
        BookingField f = new BookingField();
        f.ownerId = 1L;
        f.meetingTypeId = typeId;
        f.fieldKey = key;
        f.label = label;
        f.type = BookingField.FieldType.SHORT_TEXT;
        f.required = true;
        f.position = 0;
        f.persist();
    }
}
