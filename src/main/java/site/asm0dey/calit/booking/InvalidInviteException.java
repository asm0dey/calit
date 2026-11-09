package site.asm0dey.calit.booking;

/**
 * Thrown when a booking presents an invite that is unknown, expired, already used, or for another
 * meeting type.
 */
public class InvalidInviteException extends RuntimeException {
    public InvalidInviteException(String message) {
        super(message);
    }
}
