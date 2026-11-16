package site.asm0dey.calit.calendar;

/**
 * The provider no longer has a Mirror event that was addressed through the booking's stored {@link CalendarRef}: it was
 * deleted by hand. The caller re-projects the event (ADR 0001). Never thrown for the write-target fallback, where a
 * missing event proves nothing.
 */
public class EventGoneException extends RuntimeException {
    public EventGoneException(String eventId, Throwable cause) {
        super("Mirror event " + eventId + " is gone", cause);
    }
}
