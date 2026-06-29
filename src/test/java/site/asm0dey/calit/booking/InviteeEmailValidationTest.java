package site.asm0dey.calit.booking;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InviteeEmailValidationTest {

    @Inject
    BookingService bookingService;

    @Test
    void rejectsCrlfInjectionInEmail() {
        assertThrows(
                BookingValidationException.class,
                () -> bookingService.book(
                        1L,
                        "intro",
                        Instant.parse("2099-01-01T10:00:00Z"),
                        "Mallory",
                        "a@b.com\r\nBcc: attacker@evil.com",
                        Map.of(),
                        null,
                        null,
                        "en",
                        java.util.List.of()));
    }

    @Test
    void rejectsOversizedEmail() {
        var huge = "x".repeat(250) + "@b.com";
        assertThrows(
                BookingValidationException.class,
                () -> bookingService.book(
                        1L,
                        "intro",
                        Instant.parse("2099-01-01T10:00:00Z"),
                        "Mallory",
                        huge,
                        Map.of(),
                        null,
                        null,
                        "en",
                        java.util.List.of()));
    }

    @Test
    void rejectsMalformedEmail() {
        assertThrows(
                BookingValidationException.class,
                () -> bookingService.book(
                        1L,
                        "intro",
                        Instant.parse("2099-01-01T10:00:00Z"),
                        "Mallory",
                        "not-an-email",
                        Map.of(),
                        null,
                        null,
                        "en",
                        java.util.List.of()));
    }

    @Test
    void rejectsOversizedInviteeName() {
        var longName = "n".repeat(201);
        assertThrows(
                BookingValidationException.class,
                () -> bookingService.book(
                        1L,
                        "intro",
                        Instant.parse("2099-01-01T10:00:00Z"),
                        longName,
                        "a@b.com",
                        Map.of(),
                        null,
                        null,
                        "en",
                        java.util.List.of()));
    }

    @Test
    void rejectsOversizedAnswer() {
        var longAnswer = "x".repeat(2001);
        assertThrows(
                BookingValidationException.class,
                () -> bookingService.book(
                        1L,
                        "intro",
                        Instant.parse("2099-01-01T10:00:00Z"),
                        "Bob",
                        "a@b.com",
                        Map.of("note", longAnswer),
                        null,
                        null,
                        "en",
                        java.util.List.of()));
    }
}
