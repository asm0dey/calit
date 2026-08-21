package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/** createEvent inserts on the calendar it is given, and falls back to the write target. */
@QuarkusTest
class CreateEventTargetTest {

    private Calendar.Events events;
    private GoogleTokenService tokens;

    @Test
    @Transactional
    void insertsOnTheGivenCalendar() throws IOException {
        seedOwnerSettings();
        var credId = seedWriteTarget("sub-create-target", "default@example.com");
        seedCalendar(credId, "work@example.com");
        GoogleCalendarPort port = port();

        CreatedEvent created = port.createEvent(
                1L,
                new CalendarRef(credId, "work@example.com"),
                "s",
                "d",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                List.of("a@example.com"),
                false,
                null);

        verify(events).insert(eqCalendar("work@example.com"), any());
        assertEquals("work@example.com", created.calendar().googleCalendarId());
        assertEquals(credId, created.calendar().credentialId());
    }

    @Test
    @Transactional
    void nullTargetInsertsOnTheDefaultWriteTarget() throws IOException {
        seedOwnerSettings();
        seedWriteTarget("sub-create-null", "default@example.com");
        GoogleCalendarPort port = port();

        port.createEvent(
                1L,
                null,
                "s",
                "d",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                List.of(),
                false,
                null);

        verify(events).insert(eqCalendar("default@example.com"), any());
    }

    @Test
    @Transactional
    void unresolvableTargetInsertsOnTheDefaultWriteTarget() throws IOException {
        seedOwnerSettings();
        var credId = seedWriteTarget("sub-create-dangling", "default@example.com");
        GoogleCalendarPort port = port();

        // No GoogleCalendar row for "gone@example.com": the picker's choice was unticked since.
        port.createEvent(
                1L,
                new CalendarRef(credId, "gone@example.com"),
                "s",
                "d",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                List.of(),
                false,
                null);

        verify(events).insert(eqCalendar("default@example.com"), any());
    }

    /** Readability helper: Mockito's eq() for the calendar-id argument of events.insert. */
    private static String eqCalendar(String calendarId) {
        return argThat(calendarId::equals);
    }

    /** A port whose events.insert(...).execute() returns a fixed event. */
    private GoogleCalendarPort port() throws IOException {
        tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Insert insert = mock(Calendar.Events.Insert.class);
        when(insert.setConferenceDataVersion(anyInt())).thenReturn(insert);
        when(insert.setSendUpdates(anyString())).thenReturn(insert);
        when(insert.execute()).thenReturn(new Event().setId("evt-1").setHtmlLink("https://calendar.example"));
        events = mock(Calendar.Events.class);
        when(events.insert(anyString(), any())).thenReturn(insert);
        Calendar client = mock(Calendar.class);
        when(client.events()).thenReturn(events);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarPort(tokens, clientFactory);
    }

    /** createEvent's eventTime() reads the owner's zone from OwnerSettings. */
    private static void seedOwnerSettings() {
        OwnerSettings s = new OwnerSettings();
        s.ownerId = 1L;
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
    }

    /** Owner 1 gets one connected account and one default write-target calendar. Returns the credential id. */
    private static Long seedWriteTarget(String sub, String calendarId) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        GoogleCalendar wt = new GoogleCalendar();
        wt.ownerId = 1L;
        wt.googleCredentialId = c.id;
        wt.googleCalendarId = calendarId;
        wt.summary = "Default";
        wt.readForBusy = true;
        wt.writeTarget = true;
        wt.persist();
        return c.id;
    }

    /** A second selected (non-default) calendar on the same account. */
    private static void seedCalendar(Long credId, String calendarId) {
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = 1L;
        c.googleCredentialId = credId;
        c.googleCalendarId = calendarId;
        c.summary = calendarId;
        c.persist();
    }
}
