package site.asm0dey.calit.google;

/**
 * Where a Google event lives: the connected account it was created with plus Google's own calendar
 * id. A null ref — or a ref with a null calendar id — means "resolve the owner's default write
 * target", which is what every write did before calit-rma2 and what pre-V26 bookings still get.
 *
 * @param credentialId     {@link GoogleCredential#id}; null once that account is disconnected
 * @param googleCalendarId Google's calendar id, as stored on {@link GoogleCalendar#googleCalendarId}
 */
public record CalendarRef(Long credentialId, String googleCalendarId) {}
