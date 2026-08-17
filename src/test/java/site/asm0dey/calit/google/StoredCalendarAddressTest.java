package site.asm0dey.calit.google;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.calendar.Calendar;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;

/**
 * deleteEvent/updateEvent/updateEventDetails address the calendar they are given, not whatever
 * the owner's write target is now. A null ref (pre-V26 booking), a ref missing pieces, a ref
 * pointing at a deleted credential, or a ref belonging to another owner all fall back to the
 * write target instead.
 */
@QuarkusTest
class StoredCalendarAddressTest {

    private Calendar.Events events;
    private GoogleTokenService tokens;

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
    void deletesUsingTheStoredRefsCredentialNotTheWriteTargetsCredential() throws IOException {
        // The whole point of storing credentialId (not just googleCalendarId) is the multi-account
        // case: the owner's *current* write target may live on a different Google account than the
        // one the event was actually created on. writeAddress must mint the access token for the
        // ref's credential, not the write-target's.
        seedWriteTarget("sub-multi-a", "default@example.com");
        GoogleCredential credB = new GoogleCredential();
        credB.ownerId = 1L;
        credB.refreshToken = "rt-b";
        credB.googleSub = "sub-multi-b";
        credB.persist();
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(credB.id, "b@example.com"), "evt-multi");

        verify(events).delete("b@example.com", "evt-multi");
        verify(tokens).validAccessToken(argThat(c -> credB.id.equals(c.id)), any());
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

    @Test
    @Transactional
    void refWithNoGoogleCalendarIdFallsBackToTheWriteTarget() throws IOException {
        // credentialId present but googleCalendarId missing: writeAddress's guard must still degrade
        // rather than pass a null calendar id through to Google.
        var credId = seedWriteTarget("sub-no-calendar-id", "default@example.com");
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(credId, null), "evt-4");

        verify(events).delete("default@example.com", "evt-4");
    }

    @Test
    @Transactional
    void refWithNoCredentialIdFallsBackToTheWriteTarget() throws IOException {
        // googleCalendarId present but credentialId missing: same guard, other half.
        seedWriteTarget("sub-no-cred-id", "default@example.com");
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(null, "orphan@example.com"), "evt-5");

        verify(events).delete("default@example.com", "evt-5");
    }

    @Test
    @Transactional
    void refPointingAtADeletedCredentialFallsBackToTheWriteTarget() throws IOException {
        // Both fields present but the credential row itself is gone (account fully disconnected).
        seedWriteTarget("sub-deleted-cred", "default@example.com");
        GoogleCalendarPort port = port();

        port.deleteEvent(1L, new CalendarRef(999_999L, "gone@example.com"), "evt-6");

        verify(events).delete("default@example.com", "evt-6");
    }

    @Test
    @Transactional
    void updateEventPatchesTheStoredCalendarNotTheWriteTarget() throws IOException {
        var credId = seedWriteTarget("sub-update-stored", "default@example.com");
        seedOwnerSettings(); // updateEvent's eventTime() needs the owner's timezone
        GoogleCalendarPort port = portForPatch();

        port.updateEvent(
                1L,
                new CalendarRef(credId, "stored@example.com"),
                "evt-upd",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:30:00Z"),
                List.of());

        verify(events).patch(eq("stored@example.com"), eq("evt-upd"), any());
    }

    @Test
    @Transactional
    void updateEventDetailsPatchesTheStoredCalendarNotTheWriteTarget() throws IOException {
        var credId = seedWriteTarget("sub-update-details-stored", "default@example.com");
        GoogleCalendarPort port = portForPatch();

        port.updateEventDetails(
                1L, new CalendarRef(credId, "stored@example.com"), "evt-upd-2", "New summary", "New desc", List.of());

        verify(events).patch(eq("stored@example.com"), eq("evt-upd-2"), any());
    }

    /** A port whose events.delete(...).execute() succeeds, capturing the calendar id it was called with. */
    private GoogleCalendarPort port() throws IOException {
        tokens = mock(GoogleTokenService.class);
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

    /** A port whose events.patch(...).execute() succeeds, capturing the calendar id it was called with. */
    private GoogleCalendarPort portForPatch() throws IOException {
        tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Patch patch = mock(Calendar.Events.Patch.class);
        when(patch.setSendUpdates(anyString())).thenReturn(patch);
        events = mock(Calendar.Events.class);
        when(events.patch(anyString(), anyString(), any())).thenReturn(patch);
        Calendar client = mock(Calendar.class);
        when(client.events()).thenReturn(events);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarPort(tokens, clientFactory);
    }

    /** Owner 1's timezone, required by updateEvent's eventTime(); not otherwise seeded per-test. */
    private static void seedOwnerSettings() {
        site.asm0dey.calit.domain.OwnerSettings s = new site.asm0dey.calit.domain.OwnerSettings();
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
        wt.writeTarget = true;
        wt.persist();
        return c.id;
    }
}
