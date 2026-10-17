package site.asm0dey.calit.calendar;

import module java.base;
import com.fasterxml.jackson.annotation.JsonProperty;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * Lists the owner's available Google calendars so they can choose read/write ones.
 */
public interface CalendarListPort {
    /**
     * A calendar as reported by Google's calendarList.list.
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

    List<RemoteCalendar> listCalendars();

    /**
     * Calendars for one specific connected account.
     */
    List<RemoteCalendar> listCalendars(GoogleCredential credential);
}
