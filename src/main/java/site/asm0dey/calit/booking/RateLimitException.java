package site.asm0dey.calit.booking;

/** Feature 16: thrown when the per-email/day booking cap is exceeded. Maps to HTTP 429. */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}
