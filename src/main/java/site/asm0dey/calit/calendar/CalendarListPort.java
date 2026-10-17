package site.asm0dey.calit.calendar;

import module java.base;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lists the owner's available calendars on its Connected accounts so they can choose read/write ones.
 */
public interface CalendarListPort {
    /**
     * A calendar as reported by the provider (Google's calendarList.list, or a CalDAV calendar-home listing).
     */
    // JSON key kept from before the rename: /api/google/calendars serialises this record.
    record RemoteCalendar(@JsonProperty("googleCalendarId") String remoteId, String summary, boolean meetSupported) {
        /**
         * Convenience for callers/tests that don't care about Meet capability (defaults false).
         */
        public RemoteCalendar(String remoteId, String summary) {
            this(remoteId, summary, false);
        }
    }

    /**
     * The current owner's first Google account's calendars (the legacy single-account JSON endpoint).
     */
    List<RemoteCalendar> listCalendars();

    /**
     * Calendars on one Connected account, of any provider.
     */
    List<RemoteCalendar> listCalendars(CalendarAccount account);
}
