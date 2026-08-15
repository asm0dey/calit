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
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.CurrentOwner;

/**
 * The collaborators are hand-built rather than injected — the subject here is pure exception
 * mapping. @QuarkusTest is nonetheless required: quarkus-jacoco only records what executes inside
 * the Quarkus test run, so a plain JUnit class would pass while reporting zero coverage. The run
 * costs nothing extra — the suite reuses one fork and one Dev Services Postgres.
 */
@QuarkusTest
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
    void googleErrorWithoutParsableDetailsStillReportsTheStatus() throws IOException {
        // A non-JSON error body (proxy HTML, gateway page) leaves getDetails() null. The status still
        // has to reach the log, and appending a null message must not.
        var port = portThatFailsWith(new GoogleJsonResponseException(
                new HttpResponseException.Builder(502, "Bad Gateway", new HttpHeaders()), null));

        var thrown = assertThrows(UncheckedIOException.class, () -> port.listCalendars(new GoogleCredential()));

        assertEquals("calendarList.list failed: HTTP 502", thrown.getMessage());
    }

    @Test
    void plainIoErrorStillWrapsWithTheCallName() throws IOException {
        var port = portThatFailsWith(new IOException("connect timed out"));

        var thrown = assertThrows(UncheckedIOException.class, () -> port.listCalendars(new GoogleCredential()));

        assertEquals("calendarList.list failed", thrown.getMessage());
        assertEquals("connect timed out", thrown.getCause().getMessage());
    }
}
