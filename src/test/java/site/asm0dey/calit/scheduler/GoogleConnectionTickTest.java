package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.google.GoogleTokenService;

@QuarkusTest
class GoogleConnectionTickTest {

    @Inject
    GoogleConnectionScheduler scheduler;

    @InjectMock
    GoogleTokenService tokenService;

    @InjectMock
    EmailService emailService;

    private Long seedCred(String sub, boolean needsReconnect, Instant notifiedAt, Instant lastProbed) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // OwnerSettings row supplies the recipient email for owner 1.
            site.asm0dey.calit.domain.OwnerSettings s = site.asm0dey.calit.domain.OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new site.asm0dey.calit.domain.OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "UTC";
            s.persist();
            GoogleCredential c = new GoogleCredential();
            c.ownerId = 1L;
            c.refreshToken = "rt";
            c.googleSub = sub;
            c.needsReconnect = needsReconnect;
            c.reconnectNotifiedAt = notifiedAt;
            c.lastProbedAt = lastProbed;
            c.accountEmail = "acct@gmail.com";
            c.persist();
            return c.id;
        });
    }

    @Test
    void probeClaimsNeverProbedCredentialAndStampsLastProbedAt() {
        var id = seedCred("tick-probe", false, null, null);
        Mockito.when(tokenService.probe(anyLong(), any())).thenReturn(GoogleTokenService.ProbeResult.OK);

        scheduler.probeDueCredentials();

        verify(tokenService).probe(Mockito.eq(id), any());
        GoogleCredential c = QuarkusTransaction.requiringNew().call(() -> GoogleCredential.findById(id));
        assertNotNull(c.lastProbedAt, "probe must stamp last_probed_at");
    }

    @Test
    void notifyEmailsOwnerOnceAndStampsNotifiedAt() {
        var id = seedCred("tick-notify", true, null, Instant.now());

        scheduler.notifyPendingDisconnects();

        verify(emailService, times(1)).sendGoogleDisconnected(Mockito.eq("owner@example.com"), any(), any());
        GoogleCredential c = QuarkusTransaction.requiringNew().call(() -> GoogleCredential.findById(id));
        assertNotNull(c.reconnectNotifiedAt, "notify must stamp reconnect_notified_at");

        // Second run: already stamped -> no second email (exactly-once).
        scheduler.notifyPendingDisconnects();
        verify(emailService, times(1)).sendGoogleDisconnected(any(), any(), any());
    }
}
