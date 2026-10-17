package site.asm0dey.calit.calendar;

/**
 * Result of creating a Mirror event on any provider.
 *
 * @param eventId the provider's event id (Google event id, or the CalDAV resource path; used later for update/delete)
 * @param meetLink      the Google Meet join URL (hangoutLink), or null if none was generated; always null for CalDAV
 * @param htmlLink      the calendar.google.com web link to the event (Google), or null (CalDAV)
 * @param calendar      where the event was created — persisted on the booking so later
 *                      update/delete address this calendar even if the owner's write target moves
 */
public record CreatedEvent(String eventId, String meetLink, String htmlLink, CalendarRef calendar) {}
