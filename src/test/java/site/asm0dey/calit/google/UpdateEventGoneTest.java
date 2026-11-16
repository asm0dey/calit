package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CalendarRef;
import site.asm0dey.calit.calendar.EventGoneException;
import site.asm0dey.calit.calendar.SelectedCalendar;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * #283: patching a Mirror event Google no longer has. Through the booking's stored address the port reports it gone,
 * so the caller can re-project it (ADR 0001); through the write-target fallback a 404 proves nothing and is swallowed.
 * Hand-built collaborators, as in {@link DeleteEventAlreadyGoneTest}.
 */
@QuarkusTest
class UpdateEventGoneTest {
    private static final List<String> ATTENDEES = List.of("sam@example.com");

    private static GoogleCalendarPort portThatFailsWith(IOException failure) throws IOException {
        var tokens = mock(GoogleTokenService.class);
        when(tokens.validAccessToken(any(), any())).thenReturn("access-token");

        Calendar.Events.Patch patch = mock(Calendar.Events.Patch.class);
        when(patch.setSendUpdates(anyString())).thenReturn(patch);
        when(patch.execute()).thenThrow(failure);
        Calendar.Events events = mock(Calendar.Events.class);
        when(events.patch(anyString(), anyString(), any(Event.class))).thenReturn(patch);
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
     * Owner 1 with a Google write target; returns the stored address a booking on that calendar carries.
     */
    private static CalendarRef seed(String sub) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.persist();
        SelectedCalendar wt = new SelectedCalendar();
        wt.ownerId = 1L;
        wt.accountId = c.id;
        wt.remoteId = "wt@example.com";
        wt.summary = "WT";
        wt.writeTarget = true;
        wt.persist();
        return new CalendarRef(c.id, "wt@example.com");
    }

    private static void update(GoogleCalendarPort port, CalendarRef ref) {
        port.updateEvent(1L, ref, "evt", Instant.now(), Instant.now().plusSeconds(1800), ATTENDEES);
    }

    @Test
    @Transactional
    void storedRefNotFoundThrowsEventGone() throws Exception {
        var ref = seed("sub-upd-404");
        var port = portThatFailsWith(status(404, "Not Found"));

        assertThatThrownBy(() -> update(port, ref)).isInstanceOf(EventGoneException.class);
    }

    @Test
    @Transactional
    void storedRefGoneThrowsEventGone() throws Exception {
        var ref = seed("sub-upd-410");
        var port = portThatFailsWith(status(410, "Gone"));

        assertThatThrownBy(() -> update(port, ref)).isInstanceOf(EventGoneException.class);
    }

    @Test
    @Transactional
    void storedRefNotFoundOnDetailsThrowsEventGone() throws Exception {
        var ref = seed("sub-det-404");
        var port = portThatFailsWith(status(404, "Not Found"));

        assertThatThrownBy(() -> port.updateEventDetails(1L, ref, "evt", "T", "D", ATTENDEES))
            .isInstanceOf(EventGoneException.class);
    }

    @Test
    @Transactional
    void fallbackAddressNotFoundIsSwallowed() throws Exception {
        seed("sub-upd-fallback");
        var port = portThatFailsWith(status(404, "Not Found"));

        assertThatCode(() -> update(port, null)).doesNotThrowAnyException();
        assertThatCode(() -> port.updateEventDetails(1L, null, "evt", "T", "D", ATTENDEES)).doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void otherStatusStillThrowsUnchecked() throws Exception {
        var ref = seed("sub-upd-500");
        var port = portThatFailsWith(status(500, "Internal Server Error"));

        var thrown = assertThatExceptionOfType(UncheckedIOException.class)
            .isThrownBy(() -> update(port, ref))
            .actual();
        assertThat(thrown.getMessage()).isEqualTo("updateEvent failed");
    }
}
