package site.asm0dey.calit.booking;

/**
 * A {@link MeetingHosts} rule violation carrying an i18n message key + args instead of a
 * hardcoded English string, so the JAX-RS resource can render it localized. Extends {@link
 * IllegalStateException} so existing {@code assertThrows(IllegalStateException.class)} tests
 * (e.g. {@code MeetingHostsMutationTest}, {@code SlugCollisionTest}) keep passing unchanged.
 */
public class HostRuleException extends IllegalStateException {
    public final String messageKey;
    public final transient Object[] args;

    public HostRuleException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }
}
