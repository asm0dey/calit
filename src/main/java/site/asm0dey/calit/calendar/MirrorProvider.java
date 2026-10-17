package site.asm0dey.calit.calendar;

import io.quarkus.narayana.jta.QuarkusTransaction;

/**
 * Which provider holds (or would hold) an owner's Mirror event: the account {@code ref} names when the owner owns
 * it, else the account of the owner's write target, else {@link CalendarProvider#GOOGLE} (the pre-CalDAV default,
 * so degraded mode and pre-V26 rows behave as before).
 */
public final class MirrorProvider {
    private MirrorProvider() {
    }

    public static CalendarProvider of(Long ownerId, CalendarRef ref) {
        // joiningExisting: mail fan-out calls this outside any transaction
        return QuarkusTransaction.joiningExisting().call(() -> {
            CalendarAccount account = ref == null ? null : CalendarAccount.findOwned(ownerId, ref.accountId());
            if (account == null) {
                SelectedCalendar target = SelectedCalendar.writeTarget(ownerId);
                account = target == null ? null : CalendarAccount.findOwned(ownerId, target.accountId);
            }
            return account == null ? CalendarProvider.GOOGLE : account.provider();
        });
    }

    /**
     * True when the provider sends the attendees their invitations itself (Google), so calit must not attach its own
     * {@code .ics}; false for CalDAV, whose events carry no attendees.
     */
    public static boolean providerNotifiesAttendees(Long ownerId, CalendarRef ref) {
        return of(ownerId, ref) == CalendarProvider.GOOGLE;
    }
}
