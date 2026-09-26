package site.asm0dey.calit.email;

/**
 * What an outbox row is about, so erasure and account deletion can purge it by key instead of by
 * recipient address. Matching on the address is wrong: two owners can mail the same invitee, and
 * erasing one booking would take the other owner's mail with it.
 *
 * <p>Both fields nullable — a password-reset mail has an owner but no booking, and a mail parked
 * before V34 has neither.
 */
public record MailTag(Long bookingId, Long ownerId) {
    private static final MailTag NONE = new MailTag(null, null);

    /**
     * No link recorded — the age purge is the only thing that clears these.
     */
    public static MailTag none() {
        return NONE;
    }

    public static MailTag forBooking(Long bookingId, Long ownerId) {
        return new MailTag(bookingId, ownerId);
    }

    public static MailTag forOwner(Long ownerId) {
        return new MailTag(null, ownerId);
    }
}
