package site.asm0dey.calit.google;

import module java.base;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import site.asm0dey.calit.calendar.CalendarAccount;
import site.asm0dey.calit.calendar.SelectedCalendar;

/**
 * Validates and persists an owner's calendar selection for a set of their Connected accounts, replacing
 * those accounts' prior selection and leaving every other account's alone. Shared by the JSON REST
 * endpoint and the server-rendered page so the rules live in exactly one place:
 *  - at most one write target per owner (one saved here demotes any other account's);
 *  - the write target is always read-for-busy (else calit could double-book its own events);
 *  - every selection names one of the covered accounts, all owned by this owner.
 */
@ApplicationScoped
public class CalendarSelectionService {
    /**
     * One chosen calendar belonging to a specific connected account.
     */
    public record Selection(
            Long googleCredentialId,
            String googleCalendarId,
            String summary,
            boolean readForBusy,
            boolean writeTarget,
            boolean meetSupported
    ) {
        /**
         * Convenience for callers/tests that don't track Meet capability (defaults true, as before).
         */
        public Selection(
                Long googleCredentialId,
                String googleCalendarId,
                String summary,
                boolean readForBusy,
                boolean writeTarget
        ) {
            this(googleCredentialId, googleCalendarId, summary, readForBusy, writeTarget, true);
        }
    }

    @Transactional
    public void save(Long ownerId, Set<Long> accountIds, List<Selection> selections) {
        var writeTargets = selections.stream().filter(Selection::writeTarget).count();
        if (writeTargets > 1) {
            throw new IllegalArgumentException("At most one write-target calendar is allowed");
        }
        for (Long accountId : accountIds) {
            if (CalendarAccount.findOwned(ownerId, accountId) == null) {
                throw new IllegalArgumentException("Unknown account " + accountId + " for this owner");
            }
        }
        for (Selection sel : selections) {
            if (!accountIds.contains(sel.googleCredentialId())) {
                throw new IllegalArgumentException(
                        "Selection names account " + sel.googleCredentialId() + " outside the saved accounts"
                );
            }
        }
        if (accountIds.isEmpty()) {
            return;
        }
        SelectedCalendar.delete("ownerId = ?1 and accountId in ?2", ownerId, accountIds);
        if (writeTargets > 0) {
            SelectedCalendar.update("writeTarget = false where ownerId = ?1 and writeTarget = true", ownerId);
        }
        for (Selection sel : selections) {
            SelectedCalendar c = new SelectedCalendar();
            c.ownerId = ownerId;
            c.accountId = sel.googleCredentialId();
            c.remoteId = sel.googleCalendarId();
            c.summary = sel.summary();
            // Write target is always read for busy (hard coupling).
            c.readForBusy = sel.readForBusy() || sel.writeTarget();
            c.writeTarget = sel.writeTarget();
            c.supportsMeet = sel.meetSupported();
            c.persist();
        }
    }
}
