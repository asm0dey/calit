package site.asm0dey.calit.booking;

import module java.base;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InviteeEmailValidationTest {
    @Inject
    BookingService bookingService;

    @Test
    void rejectsCrlfInjectionInEmail() {
        var start = Instant.parse("2099-01-01T10:00:00Z");
        Map<String, String> answers = Map.of();
        List<String> guestEmails = List.of();
        assertThrows(BookingValidationException.class, () -> bookingService.book(
                1L,
                "intro",
                start,
                "Mallory",
                "a@b.com\r\nBcc: attacker@evil.com",
                answers,
                null,
                null,
                "en",
                guestEmails
        ));
    }

    @Test
    void rejectsOversizedEmail() {
        var huge = "x".repeat(250) + "@b.com";
        var start = Instant.parse("2099-01-01T10:00:00Z");
        Map<String, String> answers = Map.of();
        List<String> guestEmails = List.of();
        assertThrows(BookingValidationException.class, () -> bookingService.book(
                1L,
                "intro",
                start,
                "Mallory",
                huge,
                answers,
                null,
                null,
                "en",
                guestEmails
        ));
    }

    @Test
    void rejectsMalformedEmail() {
        var start = Instant.parse("2099-01-01T10:00:00Z");
        Map<String, String> answers = Map.of();
        List<String> guestEmails = List.of();
        assertThrows(BookingValidationException.class, () -> bookingService.book(
                1L,
                "intro",
                start,
                "Mallory",
                "not-an-email",
                answers,
                null,
                null,
                "en",
                guestEmails
        ));
    }

    @Test
    void rejectsOversizedInviteeName() {
        var longName = "n".repeat(201);
        var start = Instant.parse("2099-01-01T10:00:00Z");
        Map<String, String> answers = Map.of();
        List<String> guestEmails = List.of();
        assertThrows(BookingValidationException.class, () -> bookingService.book(
                1L,
                "intro",
                start,
                longName,
                "a@b.com",
                answers,
                null,
                null,
                "en",
                guestEmails
        ));
    }

    @Test
    void rejectsOversizedAnswer() {
        var longAnswer = "x".repeat(2001);
        var start = Instant.parse("2099-01-01T10:00:00Z");
        Map<String, String> answers = Map.of("note", longAnswer);
        List<String> guestEmails = List.of();
        assertThrows(BookingValidationException.class, () -> bookingService.book(
                1L,
                "intro",
                start,
                "Bob",
                "a@b.com",
                answers,
                null,
                null,
                "en",
                guestEmails
        ));
    }
}
