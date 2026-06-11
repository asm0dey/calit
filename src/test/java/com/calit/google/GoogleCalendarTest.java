package com.calit.google;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GoogleCalendarTest {

    @Test
    @TestTransaction
    void listsOnlyReadForBusyCalendars() {
        cal("work@example.com", "Work", true, false);
        cal("busy@example.com", "Side", true, false);
        cal("ignored@example.com", "Ignored", false, false);

        List<GoogleCalendar> readers = GoogleCalendar.readForBusy(1L);

        assertEquals(2, readers.size());
        assertTrue(readers.stream().allMatch(c -> c.readForBusy));
    }

    @Test
    @TestTransaction
    void returnsTheSingleWriteTarget() {
        cal("read@example.com", "Read", true, false);
        cal("write@example.com", "Write", false, true);

        GoogleCalendar target = GoogleCalendar.writeTarget(1L);

        assertNotNull(target);
        assertEquals("write@example.com", target.googleCalendarId);
    }

    @Test
    @TestTransaction
    void writeTargetIsNullWhenNoneSelected() {
        cal("read@example.com", "Read", true, false);
        assertNull(GoogleCalendar.writeTarget(1L));
    }

    private GoogleCalendar cal(String id, String summary, boolean read, boolean write) {
        GoogleCalendar c = new GoogleCalendar();
        c.ownerId = 1L;
        c.googleCalendarId = id;
        c.summary = summary;
        c.readForBusy = read;
        c.writeTarget = write;
        c.persist();
        return c;
    }
}
