package site.asm0dey.calit.google;

import java.time.Instant;
import java.util.List;

/**
 * Owner-level calendar operations. The real implementation talks to Google;
 * downstream tests replace it with a Mockito {@code @InjectMock}.
 */
public interface CalendarPort {

    /**
     * True iff Google is connected for the given owner — i.e. a {@link GoogleCredential}
     * (with refresh token) exists.
     * Plan 3 runs in degraded (Google-optional) mode and calls the other methods only when this is true.
     */
    boolean isConnected(Long ownerId);

    /** Merged busy intervals across the given owner's read-for-busy calendars within [from, to). */
    List<BusyInterval> freeBusy(Long ownerId, Instant from, Instant to);

    /**
     * Create an event on the given owner's write-target calendar, always with {@code sendUpdates=all}
     * so Google emails the attendees the invite.
     *
     * @param createMeetLink when true, attach a Google Meet conference (returns a non-null meetLink);
     *                       when false, no conference is created and {@code locationText} is set as the
     *                       event location instead, and the returned meetLink is null
     * @param locationText   per-type location text used when {@code createMeetLink} is false (may be null)
     */
    CreatedEvent createEvent(
            Long ownerId,
            String summary,
            String description,
            Instant start,
            Instant end,
            List<String> attendeeEmails,
            boolean createMeetLink,
            String locationText);

    /** Move an existing event to a new time window and replace its attendee list (reschedule / guest sync); {@code sendUpdates=all}. A null or empty attendee list leaves attendees unchanged. */
    void updateEvent(Long ownerId, String eventId, Instant start, Instant end, List<String> attendeeEmails);

    /** Remove an existing event (cancel); {@code sendUpdates=all}. */
    void deleteEvent(Long ownerId, String eventId);
}
