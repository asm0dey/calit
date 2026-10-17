package site.asm0dey.calit.calendar;

import site.asm0dey.calit.google.GoogleCredential;

/**
 * Where a Mirror event lives (the Stored ref): the Connected account it was created with plus the
 * provider's own calendar id. A null ref — or a ref with a null calendar id — means "resolve the owner's write target",
 * which is what every write did before calit-rma2 and what pre-V26 bookings still get.
 *
 * @param accountId     the Connected account's id ({@link GoogleCredential#id} for Google); null once that account is disconnected
 * @param remoteCalendarId the provider's calendar id (Google calendar id, or the CalDAV calendar href), as stored on {@link SelectedCalendar#remoteId}
 */
public record CalendarRef(Long accountId, String remoteCalendarId) {}
