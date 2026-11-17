package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.calendar.CreatedEvent;

@QuarkusTest
class BookServiceTest {
    @Inject
    BookingService bookingService;
    @InjectMock
    CalendarPort calendarPort;
    // Owner tz Europe/Amsterdam. Derive a future weekday from now() so the slot is never in the past.
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    // 09:00 local
    private static final Instant SLOT_09 = DAY.atTime(9, 0).atZone(ZONE).toInstant();

    @Test
    @TestTransaction
    void happyPathPersistsBookingWithMeetLinkAndFiresEvent() {
        seedSettings();
        meetingTypeWithMondayWindow("book-happy", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                anyLong(),
                any(),
                any(),
                anyString(),
                anyString(),
                eq(SLOT_09),
                any(),
                any(),
                anyBoolean(),
                any()
        ))
            .thenReturn(
                    new CreatedEvent(
                            "evt-99",
                            "https://meet.google.com/xyz-1234-pqr",
                            "https://calendar.google.com/evt-99",
                            null
                    )
            );
        // No per-type fields and the only global field (seeded description) is optional,
        // so an empty answers map books successfully.
        Booking b = bookingService.book(
                1L,
                "book-happy",
                SLOT_09,
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of()
        );

        assertThat(b.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.eventId).isEqualTo("evt-99");
        assertThat(b.meetLink).isEqualTo("https://meet.google.com/xyz-1234-pqr");
        Booking loaded = Booking.findById(b.id);
        assertThat(loaded.meetLink).isEqualTo("https://meet.google.com/xyz-1234-pqr");
        // Owner email is included as an attendee; createMeetLink=true for GOOGLE_MEET; null locationText.
        verify(calendarPort, times(1))
            .createEvent(
                    anyLong(),
                    any(),
                    eq(b.manageToken),
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
    void optionalDescriptionMayBeOmitted() {
        // The seeded global `description` field (feature 10 default) is optional,
        // so a booking that omits it still succeeds (regression guard for required-loop logic).
        seedSettings();
        meetingTypeWithMondayWindow("book-optional", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
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
            .thenReturn(new CreatedEvent("evt-opt", "https://meet.google.com/opt-1-2", "h", null));

        Booking b = bookingService.book(
                1L,
                "book-optional",
                SLOT_09,
                "Sam",
                "sam@example.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of()
        );

        assertThat(b.status).isEqualTo(BookingStatus.CONFIRMED);
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
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    "book-required-missing",
                    SLOT_09,
                    "Sam",
                    "sam@example.com",
                    Map.of(),
                    "tok",
                    "",
                    "en",
                    List.of()
            ));
    }

    @Test
    @TestTransaction
    void requiredCustomFieldPresentPersistsAndStoresAnswers() {
        seedSettings();
        MeetingType t = meetingTypeWithMondayWindow("book-required-ok", LocationType.GOOGLE_MEET, false);
        requiredField(t.id, "company", "Company");
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
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
            .thenReturn(new CreatedEvent("evt-ans", "https://meet.google.com/ans-1-2", "h", null));

        Booking b = bookingService.book(
                1L,
                "book-required-ok",
                SLOT_09,
                "Sam",
                "sam@example.com",
                Map.of("company", "Acme", "note", "extra-key-kept"),
                "tok",
                "",
                "en",
                List.of()
        );

        Booking loaded = Booking.findById(b.id);
        assertThat(loaded.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(loaded.answers)
            // The submitted answers (including the unknown extra key) are stored verbatim.
            .containsEntry("company", "Acme")
            .containsEntry("note", "extra-key-kept");
    }

    @Test
    @TestTransaction
    void doubleBookOnSameSlotThrowsConflict() {
        seedSettings();
        meetingTypeWithMondayWindow("book-double", LocationType.GOOGLE_MEET, false);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
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
            .thenReturn(new CreatedEvent("evt-1", "https://meet.google.com/a-b-c", "h", null));

        bookingService.book(
                1L,
                "book-double",
                SLOT_09,
                "First",
                "first@example.com",
                Map.of(),
                "tok",
                "",
                "en",
                List.of()
        );
        // Second attempt on the now-taken slot is rejected (the persisted booking is busy).
        // The app-level re-check catches it here; the DB exclusion constraint is the
        // cross-replica backstop (guards against concurrent inserts from multiple replicas).
        assertThatExceptionOfType(BookingConflictException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    "book-double",
                    SLOT_09,
                    "Second",
                    "second@example.com",
                    Map.of(),
                    "tok",
                    "",
                    "en",
                    List.of()
            ));
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
        for (var i = 0; i < 10; i++) {
            Booking prior = new Booking();
            prior.ownerId = 1L;
            prior.meetingTypeId = t.id;
            prior.inviteeName = "Spammer";
            prior.inviteeEmail = "spam@example.com";
            prior.startUtc = SLOT_09.plusSeconds(3600L * (i + 5));
            prior.endUtc = prior.startUtc.plusSeconds(3600);
            // status irrelevant to the per-email count
            prior.status = BookingStatus.CANCELLED;
            prior.createdAt = Instant.now();
            prior.manageToken = java.util.UUID.randomUUID().toString();
            prior.persist();
        }

        assertThatExceptionOfType(RateLimitException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    "book-cap",
                    SLOT_09,
                    "Spammer",
                    "spam@example.com",
                    Map.of(),
                    "tok",
                    "",
                    "en",
                    List.of()
            ));
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
        // keep the DAY slot inside the horizon regardless of run date
        t.horizonDays = 50_000;
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

    /**
     * Adds a required per-type custom field so formFor(typeId) returns this override.
     */
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
