package site.asm0dey.calit.google;

import com.google.api.services.calendar.model.CalendarListEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import site.asm0dey.calit.user.CurrentOwner;

/** Real CalendarListPort backed by Google's calendarList.list. @ApplicationScoped, mockable downstream. */
@ApplicationScoped
public class GoogleCalendarListPort implements CalendarListPort {

    private final GoogleTokenService tokens;
    private final GoogleCalendarClientFactory clientFactory;
    private final CurrentOwner currentOwner;

    @Inject
    public GoogleCalendarListPort(
            GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory, CurrentOwner currentOwner) {
        this.tokens = tokens;
        this.clientFactory = clientFactory;
        this.currentOwner = currentOwner;
    }

    @Override
    public List<RemoteCalendar> listCalendars(GoogleCredential credential) {
        try {
            var client = clientFactory.build(tokens.validAccessToken(credential, Instant.now()));
            List<CalendarListEntry> entries =
                    client.calendarList().list().execute().getItems();
            if (entries == null) {
                return List.of();
            }
            return entries.stream()
                    .map(e -> new RemoteCalendar(
                            e.getId(), e.getSummary() == null ? e.getId() : e.getSummary(), meetSupported(e)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("calendarList.list failed", e);
        }
    }

    @Override
    public List<RemoteCalendar> listCalendars() {
        GoogleCredential c = GoogleCredential.forOwner(currentOwner.id());
        return c == null ? List.of() : listCalendars(c);
    }

    /** A calendar supports Google Meet iff "hangoutsMeet" is among its allowed conference solutions. */
    private static boolean meetSupported(CalendarListEntry e) {
        return e.getConferenceProperties() != null
                && e.getConferenceProperties().getAllowedConferenceSolutionTypes() != null
                && e.getConferenceProperties()
                        .getAllowedConferenceSolutionTypes()
                        .contains("hangoutsMeet");
    }
}
