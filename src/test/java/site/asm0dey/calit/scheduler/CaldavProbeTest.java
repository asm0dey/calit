package site.asm0dey.calit.scheduler;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import site.asm0dey.calit.caldav.CaldavClient;
import site.asm0dey.calit.caldav.CaldavException;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;

@QuarkusTest
class CaldavProbeTest {
    @InjectSpy
    GoogleConnectionScheduler scheduler;
    @InjectMock
    CaldavClient client;
    @InjectMock
    EmailService emailService;

    private Long seed(boolean needsReconnect, Instant notifiedAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "UTC";
            s.persist();
            var a = new CaldavAccount();
            a.ownerId = 1L;
            a.serverUrl = "https://dav.example.com";
            a.username = "alice";
            a.password = "pw";
            a.principalUrl = "https://dav.example.com/principals/alice/";
            a.homeUrl = "https://dav.example.com/alice/";
            a.needsReconnect = needsReconnect;
            a.reconnectNotifiedAt = notifiedAt;
            a.persist();
            return a.id;
        });
    }

    private CaldavAccount reload(Long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.findById(id));
    }

    @Test
    void accountDeletedAfterTheClaimIsSkipped() {
        var id = seed(false, null);
        doReturn(List.of(Long.MAX_VALUE, id)).when(scheduler).claimDueForProbe();

        scheduler.probeDueCredentials();

        verify(client, times(1)).probe(any());
    }

    @Test
    void unauthorizedProbeFlagsAndEmailsOnce() {
        var id = seed(false, null);
        doThrow(new CaldavException(CaldavException.Reason.UNAUTHORIZED, 401, "no", null)).when(client).probe(any());

        scheduler.tick();
        scheduler.tick();

        var a = reload(id);
        assertThat(a.needsReconnect).isTrue();
        assertThat(a.reconnectNotifiedAt).isNotNull();
        verify(emailService, times(1))
            .sendCalendarDisconnected(
                    Mockito.eq(1L),
                    Mockito.eq("owner@example.com"),
                    Mockito.eq("alice @ dav.example.com"),
                    Mockito.eq(CalendarProvider.CALDAV),
                    any()
            );
    }

    @Test
    void successfulProbeClearsFlag() {
        var id = seed(true, Instant.now());

        scheduler.probeDueCredentials();

        var a = reload(id);
        assertThat(a.needsReconnect).isFalse();
        assertThat(a.reconnectNotifiedAt).isNull();
        assertThat(a.lastProbedAt).isNotNull();
    }

    @Test
    void serverBlipDoesNotFlag() {
        var id = seed(false, null);
        doThrow(new CaldavException(CaldavException.Reason.UNREACHABLE, 0, "down", null)).when(client).probe(any());

        scheduler.tick();

        assertThat(reload(id).needsReconnect).isFalse();
        verify(emailService, Mockito.never()).sendCalendarDisconnected(any(), any(), any(), any(), any());
    }
}
