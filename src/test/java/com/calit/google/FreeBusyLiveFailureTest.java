package com.calit.google;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the freeBusy fail-soft path: when a HEALTHY credential's Google call throws,
 * the port catches the exception, flags needsReconnect=true in a committed inner transaction,
 * and returns an empty busy list (no rethrow).
 *
 * Kept in a separate class from FreeBusyMultiAccountTest because @InjectMock replaces the
 * real bean for the whole class, and the other tests there are @Transactional (which would
 * conflict with this test's requiringNew() seeding and commit assertions).
 */
@QuarkusTest
class FreeBusyLiveFailureTest {

    @Inject
    GoogleCalendarPort port;

    @InjectMock
    GoogleTokenService tokenService;

    @Test
    void liveFailureFlagsAccountNeedsReconnectAndStaysFailSoft() {
        // Seed a healthy (not pre-flagged) credential with a read calendar in a committed
        // transaction, so the row is visible to the port and to the post-call assertion.
        Long credId = QuarkusTransaction.requiringNew().call(() -> {
            GoogleCredential c = cred(1L, "sub-live", false);
            c.persist();
            GoogleCalendar g = new GoogleCalendar();
            g.ownerId = 1L;
            g.googleCredentialId = c.id;
            g.googleCalendarId = "g1";
            g.summary = "g1";
            g.readForBusy = true;
            g.persist();
            return c.id;
        });

        // Make the per-account Google call fail (tokens.validAccessToken is called first inside
        // client(cred), so throwing here drives the per-account catch in freeBusy).
        Mockito.when(tokenService.validAccessToken(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("token dead"));

        // fail-soft: no exception propagates, busy list is empty
        List<BusyInterval> busy = port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400));
        assertTrue(busy.isEmpty(), "freeBusy must not throw on a per-account failure");

        // The credential must have been flagged needsReconnect=true in a committed transaction
        // (i.e. the flag survives the method's own transaction boundary).
        GoogleCredential reloaded = QuarkusTransaction.requiringNew()
                .call(() -> GoogleCredential.findById(credId));
        assertTrue(reloaded.needsReconnect,
                "a live Google failure must flag the account needsReconnect, committed");
    }

    private static GoogleCredential cred(long owner, String sub, boolean needsReconnect) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.needsReconnect = needsReconnect;
        return c;
    }
}
