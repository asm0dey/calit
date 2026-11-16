package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.BookingField;

class BookingInputRulesTest {
    private static BookingField field(String key, boolean required) {
        var f = new BookingField();
        f.fieldKey = key;
        f.required = required;
        return f;
    }

    @Test
    void rejectsCrlfInjectionInEmail() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInviteeEmail("a@b.com\r\nBcc: attacker@evil.com"))
            .withMessage("Enter a valid email address.");
    }

    @Test
    void rejectsOversizedEmail() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInviteeEmail("x".repeat(250) + "@b.com"))
            .withMessage("Email is too long.");
    }

    @Test
    void rejectsMalformedEmail() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInviteeEmail("not-an-email"))
            .withMessage("Enter a valid email address.");
    }

    @Test
    void rejectsOversizedInviteeName() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInputBounds("n".repeat(201), Map.of()))
            .withMessage("Name is too long.");
    }

    @Test
    void rejectsOversizedAnswer() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInputBounds("Bob", Map.of("note", "x".repeat(2001))))
            .withMessage("An answer is too long.");
    }

    @Test
    void emailOver254CharsIsRejected() {
        var at254 = "x".repeat(248) + "@b.com";
        assertThat(at254).hasSize(254);
        assertThatCode(() -> BookingInputRules.validateInviteeEmail(at254)).doesNotThrowAnyException();
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInviteeEmail("x" + at254))
            .withMessage("Email is too long.");
    }

    @Test
    void nullOrBlankEmailIsRequired() {
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInviteeEmail(null))
            .withMessage("Email is required.");
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInviteeEmail("   "))
            .withMessage("Email is required.");
    }

    @Test
    void nameOver200AndAnswerOver2000AreRejected() {
        assertThatCode(() -> BookingInputRules.validateInputBounds("n".repeat(200), Map.of("a", "x".repeat(2000))))
            .doesNotThrowAnyException();
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInputBounds("n".repeat(201), Map.of()))
            .withMessage("Name is too long.");
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateInputBounds("n", Map.of("a", "x".repeat(2001))))
            .withMessage("An answer is too long.");
    }

    @Test
    void nullNameNullAnswersAndNullAnswerValuesPass() {
        var withNull = new HashMap<String, String>();
        withNull.put("a", null);
        assertThatCode(() -> BookingInputRules.validateInputBounds(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> BookingInputRules.validateInputBounds("n", withNull)).doesNotThrowAnyException();
    }

    @Test
    void plausibleEmailShapes() {
        assertThat(List.of("a@b.com", "first.last@sub.example.org")).allMatch(BookingInputRules::isPlausibleEmail);
        assertThat(List.of(
                "a,b@c.com",
                "a b@c.com",
                "a@b\t.com",
                "@b.com",
                "a@b@c.com",
                "a@",
                "a@.b.com",
                "a@b.com.",
                "a@b..com",
                "a@localhost"
        ))
            .noneMatch(BookingInputRules::isPlausibleEmail);
    }

    @Test
    void blankRequiredFieldIsRejectedOptionalIsNot() {
        var form = List.of(field("company", true), field("note", false));
        assertThatCode(() -> BookingInputRules.validateRequiredFields(form, Map.of("company", "Acme")))
            .doesNotThrowAnyException();
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateRequiredFields(form, Map.of("company", "  ")))
            .withMessage("Required field 'company' is missing or blank");
        assertThatExceptionOfType(BookingValidationException.class)
            .isThrownBy(() -> BookingInputRules.validateRequiredFields(form, Map.of("note", "hi")))
            .withMessage("Required field 'company' is missing or blank");
    }

    @Test
    void nonBlankHoneypotIsAbuse() {
        assertThatExceptionOfType(AbuseException.class)
            .isThrownBy(() -> BookingInputRules.rejectHoneypot("http://spam"))
            .withMessage("Honeypot field was filled.");
        assertThatCode(() -> BookingInputRules.rejectHoneypot(null)).doesNotThrowAnyException();
        assertThatCode(() -> BookingInputRules.rejectHoneypot(" ")).doesNotThrowAnyException();
    }

    @Test
    void capReachedAtExactlyCap() {
        assertThatExceptionOfType(RateLimitException.class)
            .isThrownBy(() -> BookingInputRules.enforceDailyCap("a@b.com", 3, 3))
            .withMessage("Daily booking cap reached for a@b.com");
        assertThatCode(() -> BookingInputRules.enforceDailyCap("a@b.com", 2, 3)).doesNotThrowAnyException();
    }
}
