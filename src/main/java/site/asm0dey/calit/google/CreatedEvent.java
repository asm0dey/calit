package site.asm0dey.calit.google;

/**
 * Result of creating a Google Calendar event.
 *
 * @param googleEventId Google's event id (used later for update/delete)
 * @param meetLink      the Google Meet join URL (hangoutLink), or null if none was generated
 * @param htmlLink      the calendar.google.com web link to the event
 */
public record CreatedEvent(String googleEventId, String meetLink, String htmlLink) {}
