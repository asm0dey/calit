package com.calit.google;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Validates and persists an owner's calendar selection, replacing any prior selection. Shared by the
 * JSON REST endpoint and the server-rendered page so the rules live in exactly one place:
 *  - at most one write target;
 *  - the write target is always read-for-busy (else calit could double-book its own events);
 *  - every selection names a real credential owned by this owner.
 */
@ApplicationScoped
public class CalendarSelectionService {

    /** One chosen calendar belonging to a specific connected account. */
    public record Selection(Long googleCredentialId, String googleCalendarId, String summary,
                            boolean readForBusy, boolean writeTarget) {}

    @Transactional
    public void save(Long ownerId, List<Selection> selections) {
        long writeTargets = selections.stream().filter(Selection::writeTarget).count();
        if (writeTargets > 1) {
            throw new IllegalArgumentException("At most one write-target calendar is allowed");
        }
        GoogleCalendar.deleteForOwner(ownerId);
        for (Selection sel : selections) {
            GoogleCredential cred = GoogleCredential.findById(sel.googleCredentialId());
            if (cred == null || !ownerId.equals(cred.ownerId)) {
                throw new IllegalArgumentException(
                        "Unknown credential " + sel.googleCredentialId() + " for this owner");
            }
            GoogleCalendar c = new GoogleCalendar();
            c.ownerId = ownerId;
            c.googleCredentialId = sel.googleCredentialId();
            c.googleCalendarId = sel.googleCalendarId();
            c.summary = sel.summary();
            // Write target is always read for busy (hard coupling).
            c.readForBusy = sel.readForBusy() || sel.writeTarget();
            c.writeTarget = sel.writeTarget();
            c.persist();
        }
    }
}
