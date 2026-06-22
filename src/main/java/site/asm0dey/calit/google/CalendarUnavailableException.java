package site.asm0dey.calit.google;

/**
 * The owner's Google free/busy data could not be read (an account is disconnected or a live call
 * failed), so availability cannot be trusted. Callers must fail CLOSED — never offer slots — rather
 * than treat a missing busy list as "all free". Unchecked: propagates from freeBusy through
 * BookingService.availableSlots to the web layer, which renders the "temporarily unavailable" page.
 */
public class CalendarUnavailableException extends RuntimeException {
    public CalendarUnavailableException(String message) {
        super(message);
    }

    public CalendarUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
