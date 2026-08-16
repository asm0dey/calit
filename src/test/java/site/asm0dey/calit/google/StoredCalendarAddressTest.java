package site.asm0dey.calit.google;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.calendar.Calendar;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;

/**
 * deleteEvent addresses the calendar it is given, not whatever the owner's write target is now.
 * A null ref (pre-V26 booking) still falls back to the write target.
 */
@QuarkusTest
class StoredCalendarAddressTest {

    private Calendar.Events events;

    @Test
    @Transactional
    void deletesOnTheStoredCalendar() throws IOException {
        var credId = seedWriteTarget("sub-stored", "default@example.com");
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(credId, "old-work@example.com"), "evt-1");

        verify(events).delete("old-work@example.com", "evt-1");
    }

    @Test
    @Transactional
    void nullRefFallsBackToTheWriteTarget() throws IOException {
        seedWriteTarget("sub-null", "default@example.com");
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, null, "evt-2");

        verify(events).delete("default@example.com", "evt-2");
    }

    @Test
    @Transactional
    void refOfAnotherOwnersCredentialFallsBackToTheWriteTarget() throws IOException {
        seedWriteTarget("sub-foreign", "default@example.com");
        // google_credential.owner_id is FK'd to app_user(id), so the "other owner" needs a real row.
        AppUser otherOwner = AppUser.create("other-owner", "x", false);
        otherOwner.persist();
        GoogleCredential foreign = new GoogleCredential();
        foreign.ownerId = otherOwner.id;
        foreign.refreshToken = "rt";
        foreign.googleSub = "sub-999";
        foreign.persist();
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(foreign.id, "someone-else@example.com"), "evt-3");

        verify(events).delete("default@example.com", "evt-3");
    }

    /** A port whose events.delete(...).execute() succeeds, capturing the calendar id it was called with. */
    private GoogleCalendarPort port() throws IOException {
        var tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Delete delete = mock(Calendar.Events.Delete.class);
        when(delete.setSendUpdates(anyString())).thenReturn(delete);
        events = mock(Calendar.Events.class);
        when(events.delete(anyString(), anyString())).thenReturn(delete);
        Calendar client = mock(Calendar.class);
        when(client.events()).thenReturn(events);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarPort(tokens, clientFactory);
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
        wt.writeTarget = true;
        wt.persist();
        return c.id;
    }
}
