package site.asm0dey.calit.calendar;

import io.quarkus.narayana.jta.QuarkusTransaction;

/**
 * Which provider holds (or would hold) an owner's Mirror event: the account {@code ref} names when the owner owns
 * it, else the account of the owner's write target. With neither there is no Mirror event at all.
 */
public final class MirrorProvider {
    private MirrorProvider() {
    }

    /**
     * The holding provider, or null when there is no usable stored address and no write target: no Mirror event.
     */
    public static CalendarProvider holder(Long ownerId, CalendarRef ref) {
        // joiningExisting: mail fan-out calls this outside any transaction
        return QuarkusTransaction.joiningExisting().call(() -> {
            CalendarAccount account = ref == null ? null : CalendarAccount.findOwned(ownerId, ref.accountId());
            if (account == null) {
                SelectedCalendar target = SelectedCalendar.writeTarget(ownerId);
                account = target == null ? null : CalendarAccount.findOwned(ownerId, target.accountId);
            }
            return account == null ? null : account.provider();
        });
    }

    /**
     * {@link #holder} for routing a call: an address-less legacy row with no write target goes to
     * {@link CalendarProvider#GOOGLE}, as every call did before CalDAV.
     */
    public static CalendarProvider of(Long ownerId, CalendarRef ref) {
        var holder = holder(ownerId, ref);
        return holder == null ? CalendarProvider.GOOGLE : holder;
    }

    /**
     * True when the provider sends the attendees their invitations itself (Google), so calit must not attach its own
     * {@code .ics}; false for CalDAV, whose events carry no attendees, and when there is no Mirror event.
     */
    public static boolean providerNotifiesAttendees(Long ownerId, CalendarRef ref) {
        return holder(ownerId, ref) == CalendarProvider.GOOGLE;
    }
}
