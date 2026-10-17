package site.asm0dey.calit.calendar;

import module java.base;
import jakarta.enterprise.context.ApplicationScoped;
import site.asm0dey.calit.caldav.CaldavClient;
import site.asm0dey.calit.google.GoogleCalendarListPort;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * The one {@link CalendarListPort}: lists an account's calendars through its own provider.
 */
@ApplicationScoped
public class AccountCalendarListPort implements CalendarListPort {
    private final GoogleCalendarListPort google;
    private final CaldavClient caldav;

    public AccountCalendarListPort(GoogleCalendarListPort google, CaldavClient caldav) {
        this.google = google;
        this.caldav = caldav;
    }

    @Override
    public List<RemoteCalendar> listCalendars() {
        return google.listCalendars();
    }

    @Override
    public List<RemoteCalendar> listCalendars(CalendarAccount account) {
        return switch (account) {
            case GoogleCredential g -> google.listCalendars(g);
            case CaldavAccount c -> caldav.listCalendars(c);
            default -> throw new IllegalArgumentException("No calendar listing for " + account.provider());
        };
    }
}
