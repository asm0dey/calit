package site.asm0dey.calit.calendar;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.quarkus.arc.All;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.caldav.CaldavCalendarPort;
import site.asm0dey.calit.google.GoogleCalendarPort;
import site.asm0dey.calit.google.GoogleCredential;

@QuarkusTest
class RoutingCalendarPortTest {
    private static final Instant FROM = Instant.parse("2026-06-08T00:00:00Z");
    private static final Instant TO = FROM.plus(1, ChronoUnit.DAYS);
    @Inject
    CalendarPort router;
    @InjectMock
    GoogleCalendarPort google;
    @InjectMock
    CaldavCalendarPort caldav;
    @Inject
    @All
    List<ProviderCalendarPort> providerPorts;

    @BeforeEach
    void providers() {
        // a mock answers null; the router keys its map on this
        when(google.provider()).thenReturn(CalendarProvider.GOOGLE);
        when(caldav.provider()).thenReturn(CalendarProvider.CALDAV);
    }

    @Test
    void duplicateProviderFailsFast() {
        var a = mock(ProviderCalendarPort.class);
        var b = mock(ProviderCalendarPort.class);
        when(a.provider()).thenReturn(CalendarProvider.GOOGLE);
        when(b.provider()).thenReturn(CalendarProvider.GOOGLE);

        assertThatThrownBy(() -> new RoutingCalendarPort(List.of(a, b))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routerReachesEveryProviderPortBean() {
        var router = new RoutingCalendarPort(providerPorts);

        assertThat(router.providers())
            .containsExactlyInAnyOrderElementsOf(providerPorts.stream().map(ProviderCalendarPort::provider).toList())
            .containsExactlyInAnyOrder(CalendarProvider.values());
    }

    @Test
    void mergesBusyFromBothProviders() {
        when(google.isConnected(1L)).thenReturn(true);
        when(caldav.isConnected(1L)).thenReturn(true);
        when(google.freeBusy(1L, FROM, TO)).thenReturn(List.of(busy(9, 10)));
        when(caldav.freeBusy(1L, FROM, TO)).thenReturn(List.of(busy(10, 11)));

        assertThat(router.freeBusy(1L, FROM, TO)).containsExactly(busy(9, 11));
    }

    @Test
    void oneBrokenProviderFailsClosed() {
        when(google.isConnected(1L)).thenReturn(true);
        when(caldav.isConnected(1L)).thenReturn(true);
        when(google.freeBusy(1L, FROM, TO)).thenReturn(List.of(busy(9, 10)));
        when(caldav.freeBusy(1L, FROM, TO)).thenThrow(new CalendarUnavailableException("down"));

        assertThatThrownBy(() -> router.freeBusy(1L, FROM, TO)).isInstanceOf(CalendarUnavailableException.class);
    }

    @Test
    void createRoutesToWriteTargetProvider() {
        var caldavAccount = QuarkusTransaction.requiringNew().call(() -> {
            var a = caldavAccount();
            calendar(a.id, "/cal/work/", true);
            return a.id;
        });

        router.createEvent(1L, null, "uid-1", "S", "D", FROM, TO, List.of(), false, null);

        verify(caldav).createEvent(
                eq(1L),
                isNull(),
                eq("uid-1"),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        );
        verify(google, never()).createEvent(
                anyLong(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        );
        assertThat(MirrorProvider.of(1L, null)).isEqualTo(CalendarProvider.CALDAV);
        assertThat(MirrorProvider.of(1L, new CalendarRef(caldavAccount, "/cal/work/"))).isEqualTo(
                CalendarProvider.CALDAV
        );
    }

    @Test
    void deleteRoutesByStoredRef() {
        var googleRef = QuarkusTransaction.requiringNew().call(() -> {
            var g = new GoogleCredential();
            g.ownerId = 1L;
            g.refreshToken = "rt";
            g.googleSub = "sub-routing";
            g.persist();
            calendar(g.id, "old@example.com", false);
            calendar(caldavAccount().id, "/cal/work/", true);
            return new CalendarRef(g.id, "old@example.com");
        });

        router.deleteEvent(1L, googleRef, "evt-1");

        verify(google).deleteEvent(1L, googleRef, "evt-1");
        verify(caldav, never()).deleteEvent(anyLong(), any(), any());
    }

    @Test
    void unknownRefAndNoWriteTargetRouteToGoogleButHaveNoHolder() {
        assertThat(MirrorProvider.of(1L, new CalendarRef(999L, "nope"))).isEqualTo(CalendarProvider.GOOGLE);
        assertThat(MirrorProvider.holder(1L, null)).isNull();
    }

    @Test
    void createWithNoWriteCalendarMakesNoMirrorEvent() {
        QuarkusTransaction.requiringNew().run(RoutingCalendarPortTest::caldavAccount);

        assertThat(router.createEvent(1L, null, "uid-2", "S", "D", FROM, TO, List.of(), false, null)).isNull();
        verify(caldav, never()).createEvent(
                anyLong(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        );
        verify(google, never()).createEvent(
                anyLong(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        );
    }

    private static BusyInterval busy(int fromHour, int toHour) {
        return new BusyInterval(FROM.plus(fromHour, ChronoUnit.HOURS), FROM.plus(toHour, ChronoUnit.HOURS));
    }

    static CaldavAccount caldavAccount() {
        var a = new CaldavAccount();
        a.ownerId = 1L;
        a.serverUrl = "https://dav.example.com/";
        a.username = "alice";
        a.password = "secret";
        a.principalUrl = "https://dav.example.com/p/" + UUID.randomUUID() + "/";
        a.homeUrl = "https://dav.example.com/cal/";
        a.persist();
        return a;
    }

    static void calendar(Long accountId, String remoteId, boolean writeTarget) {
        var c = new SelectedCalendar();
        c.ownerId = 1L;
        c.accountId = accountId;
        c.remoteId = remoteId;
        c.summary = remoteId;
        c.writeTarget = writeTarget;
        c.persist();
    }
}
