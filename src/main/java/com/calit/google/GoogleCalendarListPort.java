package com.calit.google;

import com.google.api.services.calendar.model.CalendarListEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

/** Real CalendarListPort backed by Google's calendarList.list. @ApplicationScoped, mockable downstream. */
@ApplicationScoped
public class GoogleCalendarListPort implements CalendarListPort {

    private final GoogleTokenService tokens;
    private final GoogleCalendarClientFactory clientFactory;
    private final com.calit.user.CurrentOwner currentOwner;

    @Inject
    public GoogleCalendarListPort(GoogleTokenService tokens, GoogleCalendarClientFactory clientFactory,
                                  com.calit.user.CurrentOwner currentOwner) {
        this.tokens = tokens;
        this.clientFactory = clientFactory;
        this.currentOwner = currentOwner;
    }

    @Override
    public List<RemoteCalendar> listCalendars(GoogleCredential credential) {
        try {
            var client = clientFactory.build(tokens.validAccessToken(credential, Instant.now()));
            List<CalendarListEntry> entries = client.calendarList().list().execute().getItems();
            if (entries == null) {
                return List.of();
            }
            return entries.stream()
                    .map(e -> new RemoteCalendar(e.getId(),
                            e.getSummary() == null ? e.getId() : e.getSummary()))
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
}
