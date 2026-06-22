package site.asm0dey.calit.booking;

/** Thrown when submitted booking-form input is invalid (e.g. a required custom field is missing). Maps to HTTP 422. */
public class BookingValidationException extends RuntimeException {
    public BookingValidationException(String message) {
        super(message);
    }
}
