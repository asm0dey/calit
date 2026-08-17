package site.asm0dey.calit.google;

/**
 * Result of creating a Google Calendar event.
 *
 * @param googleEventId Google's event id (used later for update/delete)
 * @param meetLink      the Google Meet join URL (hangoutLink), or null if none was generated
 * @param htmlLink      the calendar.google.com web link to the event
 * @param calendar      where the event was created — persisted on the booking so later
 *                      update/delete address this calendar even if the owner's write target moves
 */
public record CreatedEvent(String googleEventId, String meetLink, String htmlLink, CalendarRef calendar) {}
