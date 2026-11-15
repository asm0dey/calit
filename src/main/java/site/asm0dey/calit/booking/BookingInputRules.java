package site.asm0dey.calit.booking;

import module java.base;
import site.asm0dey.calit.domain.BookingField;

/**
 * Pure checks on booking-form input: email shape, length bounds, required fields, honeypot and the daily cap.
 */
public final class BookingInputRules {
    private BookingInputRules() {
    }

    static void validateInviteeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BookingValidationException("Email is required.");
        }
        if (email.length() > 254) {
            throw new BookingValidationException("Email is too long.");
        }
        if (!isPlausibleEmail(email)) {
            throw new BookingValidationException("Enter a valid email address.");
        }
    }

    // RFC-pragmatic single-address check done WITHOUT a regex (so there is no ReDoS or regex-engine
    // stack-overflow risk on hostile input): exactly one non-leading '@', a domain with at least one
    // dot and no empty labels, and no whitespace or comma anywhere — the latter blocks header/ICS
    // injection and CRLF smuggling. Not a full RFC 5322 parser (SEC-INPUT-01).
    static boolean isPlausibleEmail(String email) {
        for (var i = 0; i < email.length(); i++) {
            var ch = email.charAt(i);
            if (ch == ',' || Character.isWhitespace(ch)) {
                // commas and CR/LF/space/tab are injection vectors — reject
                return false;
            }
        }
        var at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            // need exactly one '@', neither first nor last char
            return false;
        }
        var domain = email.substring(at + 1);
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) {
            // no empty domain labels
            return false;
        }
        // domain must have at least one dot
        return domain.indexOf('.') >= 0;
    }

    static void validateInputBounds(String inviteeName, Map<String, String> answers) {
        if (inviteeName != null && inviteeName.length() > 200) {
            throw new BookingValidationException("Name is too long.");
        }
        if (answers != null) {
            for (String v : answers.values()) {
                if (v != null && v.length() > 2000) {
                    throw new BookingValidationException("An answer is too long.");
                }
            }
        }
    }

    /**
     * Feature 10: rejects the booking (HTTP 422) if any required field in {@code form} is missing or blank in the
     * submitted answers.
     */
    static void validateRequiredFields(List<BookingField> form, Map<String, String> answers) {
        for (BookingField field : form) {
            if (field.required) {
                String value = answers.get(field.fieldKey);
                if (value == null || value.isBlank()) {
                    throw new BookingValidationException("Required field '"
                            + field.fieldKey
                            + "' is missing or blank");
                }
            }
        }
    }

    static void rejectHoneypot(String honeypot) {
        if (honeypot != null && !honeypot.isBlank()) {
            // a bot filled the hidden field
            // -> AbuseException (400)
            throw new AbuseException("Honeypot field was filled.");
        }
    }

    static void enforceDailyCap(String email, long todaysCount, long cap) {
        if (todaysCount >= cap) {
            throw new RateLimitException("Daily booking cap reached for " + email);
        }
    }
}
