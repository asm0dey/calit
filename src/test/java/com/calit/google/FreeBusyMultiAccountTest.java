package com.calit.google;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FreeBusyMultiAccountTest {

    @Inject
    GoogleCalendarPort port;

    @Test
    @Transactional
    void allAccountsFlaggedNeedsReconnectYieldsEmptyAndNoThrow() {
        GoogleCredential a = cred(1L, "sub-A", true);
        a.persist();
        readCal(1L, a.id, "a-cal");
        List<BusyInterval> busy = port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400));
        assertTrue(busy.isEmpty());
    }

    @Test
    @Transactional
    void noReadCalendarsYieldsEmpty() {
        assertTrue(port.freeBusy(1L, Instant.now(), Instant.now().plusSeconds(86400)).isEmpty());
    }

    private static GoogleCredential cred(long owner, String sub, boolean needsReconnect) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = owner; c.refreshToken = "rt"; c.googleSub = sub; c.needsReconnect = needsReconnect;
        return c;
    }

    private static void readCal(long owner, long credId, String calId) {
        GoogleCalendar g = new GoogleCalendar();
        g.ownerId = owner; g.googleCredentialId = credId; g.googleCalendarId = calId;
        g.summary = calId; g.readForBusy = true; g.persist();
    }
}
