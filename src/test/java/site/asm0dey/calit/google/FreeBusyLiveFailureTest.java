package site.asm0dey.calit.google;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * freeBusy is fail-CLOSED: a broken busy-feeding account must make the whole call throw
 * CalendarUnavailableException rather than return an empty/partial busy list (which would render
 * every slot as available). Covers both a known-broken (needsReconnect) account and a live failure.
 */
@QuarkusTest
class FreeBusyLiveFailureTest {

    @Inject
    GoogleCalendarPort port;

    @InjectMock
    GoogleTokenService tokenService;

    @Test
    void liveFailureThrowsCalendarUnavailable() {
        Long credId = seedHealthyCredWithReadCalendar("sub-live");
        // validAccessToken is called first inside client(cred); throwing here drives the live-failure path.
        Mockito.when(tokenService.validAccessToken(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("token dead"));

        assertThrows(CalendarUnavailableException.class, () ->
                port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400)));
    }

    @Test
    void knownBrokenAccountThrowsCalendarUnavailableWithoutCallingGoogle() {
        seedReadCalendarForCred(seedCred("sub-broken", true)); // pre-flagged needsReconnect

        assertThrows(CalendarUnavailableException.class, () ->
                port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400)));
        // No tokenService interaction: a known-broken account short-circuits before any Google call.
        Mockito.verifyNoInteractions(tokenService);
    }

    // --- helpers ---

    private Long seedHealthyCredWithReadCalendar(String sub) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Long id = seedCred(sub, false);
            seedReadCalendarForCred(id);
            return id;
        });
    }

    private Long seedCred(String sub, boolean needsReconnect) {
        return QuarkusTransaction.requiringNew().call(() -> {
            GoogleCredential c = new GoogleCredential();
            c.ownerId = 1L;
            c.refreshToken = "rt";
            c.googleSub = sub;
            c.needsReconnect = needsReconnect;
            c.persist();
            return c.id;
        });
    }

    private void seedReadCalendarForCred(Long credId) {
        QuarkusTransaction.requiringNew().run(() -> {
            GoogleCalendar g = new GoogleCalendar();
            g.ownerId = 1L;
            g.googleCredentialId = credId;
            g.googleCalendarId = "g-" + credId;
            g.summary = "cal";
            g.readForBusy = true;
            g.persist();
        });
    }
}
