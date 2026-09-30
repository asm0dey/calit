package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.calendar.Calendar;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

/**
 * Deleting an event that Google already deleted is idempotent from our side: the desired end state
 * (no event on Google) holds, so cancel must proceed. Anything else still fails loudly.
 *
 * <p>Collaborators are hand-built (mocked token service + client factory) like
 * {@link GoogleCalendarListPortTest}; the write-target row is seeded because deleteEvent resolves it
 * from the DB. The test method carries {@code @Transactional} since a hand-built port gets no CDI
 * interception.
 */
@QuarkusTest
class DeleteEventAlreadyGoneTest {
    /**
     * Wire a port whose events.delete(...).execute() fails with the given exception.
     */
    private static GoogleCalendarPort portThatFailsWith(IOException failure) throws IOException {
        var tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Delete delete = mock(Calendar.Events.Delete.class);
        when(delete.setSendUpdates(anyString())).thenReturn(delete);
        when(delete.execute()).thenThrow(failure);
        Calendar.Events events = mock(Calendar.Events.class);
        when(events.delete(anyString(), anyString())).thenReturn(delete);
        Calendar client = mock(Calendar.class);
        when(client.events()).thenReturn(events);

        var clientFactory = mock(GoogleCalendarClientFactory.class);
        when(clientFactory.build(any())).thenReturn(client);

        return new GoogleCalendarPort(tokens, clientFactory);
    }

    private static GoogleJsonResponseException status(int code, String reason) {
        return new GoogleJsonResponseException(new HttpResponseException.Builder(code, reason, new HttpHeaders()), null);
    }

    /**
     * deleteEvent reads the owner's write target from the DB; give owner 1 one.
     */
    private static void seedWriteTarget(String sub) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        GoogleCalendar wt = new GoogleCalendar();
        wt.ownerId = 1L;
        wt.googleCredentialId = c.id;
        wt.googleCalendarId = "wt@example.com";
        wt.summary = "WT";
        wt.writeTarget = true;
        wt.persist();
    }

    @Test
    @Transactional
    void goneEventIsTreatedAsDeleted() throws Exception {
        seedWriteTarget("sub-gone");
        var port = portThatFailsWith(status(410, "Gone"));

        assertThatCode(() -> port.deleteEvent(1L, null, "evt-gone")).doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void missingEventIsTreatedAsDeleted() throws Exception {
        seedWriteTarget("sub-missing");
        var port = portThatFailsWith(status(404, "Not Found"));

        assertThatCode(() -> port.deleteEvent(1L, null, "evt-missing")).doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void otherGoogleFailuresStillThrow() throws Exception {
        seedWriteTarget("sub-boom");
        var port = portThatFailsWith(status(500, "Internal Server Error"));

        var thrown = assertThatExceptionOfType(UncheckedIOException.class)
            .isThrownBy(() -> port.deleteEvent(1L, null, "evt-boom"))
            .actual();
        assertThat(thrown.getMessage()).isEqualTo("deleteEvent failed");
    }

    @Test
    @Transactional
    void plainIoErrorStillThrows() throws Exception {
        seedWriteTarget("sub-timeout");
        var port = portThatFailsWith(new IOException("connect timed out"));

        var thrown = assertThatExceptionOfType(UncheckedIOException.class)
            .isThrownBy(() -> port.deleteEvent(1L, null, "evt-timeout"))
            .actual();
        assertThat(thrown.getCause().getMessage()).isEqualTo("connect timed out");
    }
}
