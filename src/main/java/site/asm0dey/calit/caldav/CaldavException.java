package site.asm0dey.calit.caldav;

/**
 * A CalDAV call failed; {@link #reason()} says why so callers can map it to a user message.
 */
public class CaldavException extends RuntimeException {
    public enum Reason {
        PRIVATE_HOST,
        UNAUTHORIZED,
        NOT_CALDAV,
        UNREACHABLE,
        HTTP_ERROR
    }

    private final Reason reason;
    private final int status;

    public CaldavException(Reason reason, String message) {
        this(reason, 0, message, null);
    }

    public CaldavException(Reason reason, int status, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.status = status;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * The HTTP status, or 0 when there was none.
     */
    public int status() {
        return status;
    }
}
