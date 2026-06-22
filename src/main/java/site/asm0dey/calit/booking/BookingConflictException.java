package site.asm0dey.calit.booking;

/** Thrown when a requested slot is no longer available (double-book / race). Maps to HTTP 409. */
public class BookingConflictException extends RuntimeException {
    public BookingConflictException(String message) {
        super(message);
    }
}
