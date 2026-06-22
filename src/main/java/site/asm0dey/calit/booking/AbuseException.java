package site.asm0dey.calit.booking;

/** Feature 16: thrown when a public-form abuse guard (e.g. Turnstile) rejects the request. Maps to HTTP 400. */
public class AbuseException extends RuntimeException {
    public AbuseException(String message) {
        super(message);
    }
}
