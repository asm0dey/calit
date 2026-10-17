package site.asm0dey.calit.google;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.calendar.CalendarUnavailableException;
import site.asm0dey.calit.calendar.SelectedCalendar;

@QuarkusTest
class FreeBusyMultiAccountTest {
    @Inject
    GoogleCalendarPort port;

    @Test
    @Transactional
    void brokenBusyFeedingAccountFailsClosed() {
        // Fail-CLOSED: a needsReconnect busy-feeding account must make freeBusy throw, not return
        // an empty (all-free) list that would let a conflicting booking slip through.
        GoogleCredential a = cred(1L, "sub-A", true);
        a.persist();
        readCal(1L, a.id, "a-cal");
        var from = Instant.now();
        var to = Instant.now().plusSeconds(86400);
        assertThatExceptionOfType(CalendarUnavailableException.class).isThrownBy(() -> port.freeBusy(1L, from, to));
    }

    @Test
    @Transactional
    void noReadCalendarsYieldsEmpty() {
        assertThat(port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400))).isEmpty();
    }

    private static GoogleCredential cred(long owner, String sub, boolean needsReconnect) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner;
        c.refreshToken = "rt";
        c.googleSub = sub;
        c.needsReconnect = needsReconnect;
        return c;
    }

    private static void readCal(long owner, long credId, String calId) {
        SelectedCalendar g = new SelectedCalendar();
        g.ownerId = owner;
        g.accountId = credId;
        g.remoteId = calId;
        g.summary = calId;
        g.readForBusy = true;
        g.persist();
    }
}
