package site.asm0dey.calit.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.calendar.Calendar;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * Plain unit test (no @QuarkusTest): the point is the exception mapping, and booting Quarkus for it
 * would cost a Postgres container per run.
 */
class GoogleCalendarListPortTest {

    /** Wire a port whose Calendar client fails the calendarList.list call with the given exception. */
    private static GoogleCalendarListPort portThatFailsWith(IOException failure) throws IOException {
        var tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.CalendarList.List list = mock(Calendar.CalendarList.List.class);
        when(list.execute()).thenThrow(failure);
        Calendar.CalendarList calendarList = mock(Calendar.CalendarList.class);
        when(calendarList.list()).thenReturn(list);
        Calendar client = mock(Calendar.class);
        when(client.calendarList()).thenReturn(calendarList);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarListPort(tokens, clientFactory, mock(CurrentOwner.class));
    }

    private static GoogleJsonResponseException serviceDisabled() {
        var details = new GoogleJsonError();
        details.setCode(403);
        details.setMessage("Google Calendar API has not been used in project 477339155409 before or it is disabled.");
        return new GoogleJsonResponseException(
                new HttpResponseException.Builder(403, "Forbidden", new HttpHeaders()), details);
    }

    @Test
    void googleErrorCarriesStatusAndMessageOnTheFirstLine() throws IOException {
        var port = portThatFailsWith(serviceDisabled());

        var thrown = assertThrows(UncheckedIOException.class, () -> port.listCalendars(new GoogleCredential()));

        // Operators read the first line of the WARN; the status and Google's own words must be there.
        assertTrue(thrown.getMessage().contains("HTTP 403"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("has not been used in project"), thrown.getMessage());
    }

    @Test
    void plainIoErrorStillWrapsWithTheCallName() throws IOException {
        var port = portThatFailsWith(new IOException("connect timed out"));

        var thrown = assertThrows(UncheckedIOException.class, () -> port.listCalendars(new GoogleCredential()));

        assertEquals("calendarList.list failed", thrown.getMessage());
        assertEquals("connect timed out", thrown.getCause().getMessage());
    }
}
