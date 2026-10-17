package site.asm0dey.calit.calendar;

import module java.base;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * Owner-level calendar operations on Mirror events and busy time. Implemented per provider (Google, CalDAV)
 * and combined by {@code RoutingCalendarPort}; downstream tests replace it with a Mockito
 * {@code @InjectMock}.
 */
public interface CalendarPort {
    /**
     * True iff the owner has a Connected account of this provider (for Google, a {@link GoogleCredential}
     * with a refresh token); the routing port answers true when any provider is connected. When false
     * calit runs in Degraded mode and callers use the other methods only when this is true.
     */
    boolean isConnected(Long ownerId);

    /**
     * Merged busy intervals across the given owner's read-for-busy calendars within [from, to).
     */
    List<BusyInterval> freeBusy(Long ownerId, Instant from, Instant to);

    /**
     * Create a Mirror event on the given calendar. Google creates it with {@code sendUpdates=all}, so
     * Google emails the attendees the invite. CalDAV Mirror events carry no attendees (the list goes into
     * the description), so calit sends the invites itself.
     *
     * @param target         where to create it: the meeting type's resolved write calendar. Null — or
     *                       one that no longer names a selected calendar — falls back to the owner's
     *                       write target, which is what every create did before calit-bh5t
     * @param uid            stable event identity (the lead booking's manage token); a provider that
     *                       names events itself may ignore it
     * @param createMeetLink when true, attach a Google Meet conference (returns a non-null meetLink).
     *                       Google-only: CalDAV ignores it and never returns a meetLink. When false, no
     *                       conference is created and {@code locationText} is set as the event location
     *                       instead, and the returned meetLink is null
     * @param locationText   per-type location text used when {@code createMeetLink} is false (may be null)
     * @return the created event, or null when the owner has no write calendar to put it on (no Mirror event; the
     *     booking still goes ahead)
     */
    CreatedEvent createEvent(
            Long ownerId,
            CalendarRef target,
            String uid,
            String summary,
            String description,
            Instant start,
            Instant end,
            List<String> attendeeEmails,
            boolean createMeetLink,
            String locationText
    );

    /**
     * Move an existing event to a new time window and replace its attendee list (reschedule / guest
     * sync); Google uses {@code sendUpdates=all}, CalDAV notifies nobody (calit sends the invites). A null or empty attendee list leaves attendees unchanged.
     *
     * @param ref where the event lives; null resolves the owner's write target (pre-V26 rows)
     */
    void updateEvent(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            Instant start,
            Instant end,
            List<String> attendeeEmails
    );

    /**
     * Patch an existing event's summary + description (and re-sync attendees), leaving its time
     * untouched; Google uses {@code sendUpdates=all} so it re-notifies everyone, CalDAV notifies nobody (calit
     * sends the invites). Used when the host/invitee
     * edits the meeting's name, description, or guest list. A null or empty attendee list leaves
     * attendees unchanged.
     *
     * @param ref where the event lives; null resolves the owner's write target (pre-V26 rows)
     */
    void updateEventDetails(
            Long ownerId,
            CalendarRef ref,
            String eventId,
            String summary,
            String description,
            List<String> attendeeEmails
    );

    /**
     * Remove an existing Mirror event (cancel); Google uses {@code sendUpdates=all}. This operation is idempotent: an
     * event that is already gone on the provider's side counts as success.
     *
     * @param ref where the event lives; null resolves the owner's write target (pre-V26 rows)
     */
    void deleteEvent(Long ownerId, CalendarRef ref, String eventId);
}
