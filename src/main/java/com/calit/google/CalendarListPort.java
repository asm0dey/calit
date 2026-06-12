package com.calit.google;

import java.util.List;

/** Lists the owner's available Google calendars so they can choose read/write ones. */
public interface CalendarListPort {

    /** A calendar as reported by Google's calendarList.list. */
    record RemoteCalendar(String googleCalendarId, String summary) {}

    List<RemoteCalendar> listCalendars();

    /** Calendars for one specific connected account. */
    List<RemoteCalendar> listCalendars(GoogleCredential credential);
}
