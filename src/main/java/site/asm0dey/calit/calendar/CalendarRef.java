package site.asm0dey.calit.calendar;

import site.asm0dey.calit.google.GoogleCredential;

/**
 * Where a Google event lives: the connected account it was created with plus Google's own calendar
 * id. A null ref — or a ref with a null calendar id — means "resolve the owner's write target",
 * which is what every write did before calit-rma2 and what pre-V26 bookings still get.
 *
 * @param accountId     {@link GoogleCredential#id}; null once that account is disconnected
 * @param remoteCalendarId Google's calendar id, as stored on {@link SelectedCalendar#remoteId}
 */
public record CalendarRef(Long accountId, String remoteCalendarId) {}
