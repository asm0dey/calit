package site.asm0dey.calit.booking;

/**
 * A {@link SetRules} failure: {@code messageKey} names the {@code AppMessages} sentence a page shows, {@code limit} its
 * count argument (the minimum or maximum; unused by the overlap and same-day keys).
 */
public class SetRuleException extends BookingValidationException {
    public final int limit;

    public SetRuleException(String message, String messageKey, int limit) {
        super(message, messageKey);
        this.limit = limit;
    }
}
