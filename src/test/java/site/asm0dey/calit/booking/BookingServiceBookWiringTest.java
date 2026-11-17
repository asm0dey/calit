package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CreatedEvent;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;

/**
 * {@link BookingService} book wiring over mocked repositories and ports: no database (#343).
 */
class BookingServiceBookWiringTest extends BookingServiceWiringFixture {
    @Test
    void rejectsCrlfInjectionInEmail() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    "intro",
                    Instant.parse("2099-01-01T10:00:00Z"),
                    "Mallory",
                    "a@b.com\r\nBcc: attacker@evil.com",
                    Map.of(),
                    null,
                    null,
                    "en",
                    List.of()
            ));
    }

    @Test
    void rejectsOversizedInviteeName() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    "intro",
                    Instant.parse("2099-01-01T10:00:00Z"),
                    "n".repeat(201),
                    "a@b.com",
                    Map.of(),
                    null,
                    null,
                    "en",
                    List.of()
            ));
    }

    @Test
    void autoTypeConnectedNonMeetLocationPassesLocationTextAndNoMeetLink() {
        // Feature 13: PHONE location -> createMeetLink=false, locationText=locationDetail.
        MeetingType t = type(false);
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+31 20 123 4567";
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        createEventReturns(new CreatedEvent("evt-ph", null, "h", null));

        Booking b =
                bookingService.book(1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        assertThat(b.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.meetLink).as("no Meet link for a non-Meet location").isNull();
        verify(calendarPort, times(1))
            .createEvent(
                    anyLong(),
                    any(),
                    any(),
                    anyString(),
                    anyString(),
                    any(),
                    any(),
                    any(),
                    eq(false),
                    eq("+31 20 123 4567")
            );
    }

    @Test
    void autoTypeDisconnectedConfirmsWithoutEventAndNullMeetLink() {
        // Degraded mode (feature 2 optional): not connected -> no Google event, null googleEventId/meetLink.
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        Booking b =
                bookingService.book(1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        assertThat(b.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.eventId).isNull();
        assertThat(b.meetLink).isNull();
        // createEvent and freeBusy must never be called when disconnected.
        verifyNoEventCreated();
        verify(calendarPort, never()).freeBusy(anyLong(), any(), any());
    }

    @Test
    void approvalTypeCreatesPendingWithoutEventAndFiresRequested() {
        // Feature 14: requiresApproval -> PENDING hold, NO Google event, BookingRequested fired.
        MeetingType t = type(true);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);

        Booking b =
                bookingService.book(1L, t.slug, SLOT_09, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        assertThat(b.status).isEqualTo(BookingStatus.PENDING);
        assertThat(b.eventId).isNull();
        assertThat(b.meetLink).isNull();
        // The PENDING request must NOT touch Google.
        verifyNoEventCreated();
        verify(requested, times(1).description("BookingRequested fired for approval type")).fire(any());
        verify(confirmed, never().description("BookingConfirmed NOT fired for a PENDING request")).fire(any());
    }

    @Test
    void filledHoneypotThrowsAbuse() {
        // Feature 16: a non-empty honeypot means a bot filled the hidden "website" field.
        // It is rejected INSIDE book() with AbuseException (HTTP 400), like a failed Turnstile,
        // before any booking is persisted. The honeypot guard is independent of the Turnstile flag.
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);

        assertThatExceptionOfType(AbuseException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    t.slug,
                    SLOT_09,
                    "Bot",
                    "bot@example.com",
                    Map.of(),
                    "tok",
                    "http://spam.example",
                    "en",
                    List.of()
            ));
    }

    @Test
    void bookingAtUnavailableStartThrowsConflict() {
        MeetingType t = type(false);
        slotFree(SLOT_09);
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        // 09:13 is not a generated slot start.
        var start = SLOT_09.plus(13, ChronoUnit.MINUTES);
        Map<String, String> answers = Map.of();
        List<String> guestEmails = List.of();
        assertThatExceptionOfType(BookingConflictException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    t.slug,
                    start,
                    "X",
                    "x@example.com",
                    answers,
                    "tok",
                    "",
                    "en",
                    guestEmails
            ));
    }

    @Test
    void bookingUnknownSlugThrowsNotFound() {
        type(false);
        Map<String, String> answers = Map.of();
        List<String> guestEmails = List.of();
        assertThatExceptionOfType(jakarta.ws.rs.NotFoundException.class)
            .isThrownBy(() -> bookingService.book(
                    1L,
                    "no-such-slug",
                    SLOT_09,
                    "X",
                    "x@example.com",
                    answers,
                    "tok",
                    "",
                    "en",
                    guestEmails
            ));
    }

    @Test
    void bookingAtAChosenLengthSetsTheEndAccordingly() {
        MeetingType t = type(false);
        t.durationMinutes = 30;
        slotsFree(120, SLOT_09);
        // 12-arg order: ownerId, slug, startUtc, name, email, answers, turnstileToken,
        // altchaSolution, honeypot, locale, guestEmails, durationMinutes.
        Booking b = bookingService.book(
                OWNER,
                t.slug,
                SLOT_09,
                "Ada",
                "ada@example.test",
                Map.of(),
                null,
                null,
                null,
                "en",
                List.of(),
                120
        );

        assertThat(Duration.between(b.startUtc, b.endUtc)).hasMinutes(120);
    }

    @Test
    void theDefaultingOverloadStillBooksTheTypesOwnLength() {
        MeetingType t = type(false);
        t.durationMinutes = 30;
        slotsFree(30, SLOT_09);

        Booking b = bookingService.book(
                OWNER,
                t.slug,
                SLOT_09,
                "Ada",
                "ada@example.test",
                Map.of(),
                null,
                null,
                null,
                "en",
                List.of()
        );

        assertThat(Duration.between(b.startUtc, b.endUtc)).hasMinutes(30);
    }
}
