package site.asm0dey.calit.email;

/**
 * Which headline a booking email opens with, per recipient. The templates switch on it; the copy itself stays in the
 * message bundles.
 */
public enum EmailHeadline {
    /**
     * The host reads that they acted themselves (only a solo host cancel says so).
     */
    SELF,
    /**
     * Neutral wording: the actor's own copy, or a host who did not act.
     */
    PASSIVE,
    /**
     * The invitee reads that the host acted.
     */
    BY_HOST,
    /**
     * The host reads that the invitee acted.
     */
    BY_INVITEE;
    static EmailHeadline of(boolean byOwner, boolean ownerRecipient, boolean hostActed) {
        if (byOwner) {
            return ownerRecipient ? hostActed ? SELF : PASSIVE : BY_HOST;
        }
        return ownerRecipient ? BY_INVITEE : PASSIVE;
    }
}
