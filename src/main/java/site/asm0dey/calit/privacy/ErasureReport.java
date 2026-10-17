package site.asm0dey.calit.privacy;

/**
 * Per-destination outcome of one erasure. The done page reports this rather than a blanket
 * success: calit sends invitee data to places it cannot reach back into, and the invitee is owed
 * an honest answer about which copies are actually gone.
 */
public record ErasureReport(GoogleOutcome google, boolean channelsWereUsed, boolean mailWasDelivered) {
    public enum GoogleOutcome {
        /**
         * No Mirror event existed for this booking (no write calendar, or already cancelled).
         */
        NOT_APPLICABLE,
        /**
         * The delete call was made. The provider may keep the event in trash or backups afterwards
         * (Google: roughly 30 days) — "removed" is not "unrecoverable".
         */
        REMOVED,
        /**
         * An event id was recorded but the calendar account is gone, so the remote copy could not be touched.
         */
        UNREACHABLE
    }

    /**
     * True when at least one copy is known to be beyond calit's reach — the done page says so.
     */
    public boolean hasUnreachableCopies() {
        return google == GoogleOutcome.UNREACHABLE || channelsWereUsed || mailWasDelivered;
    }
}
