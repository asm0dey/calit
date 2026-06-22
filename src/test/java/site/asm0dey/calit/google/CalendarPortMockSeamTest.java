package site.asm0dey.calit.google;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class CalendarPortMockSeamTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BusySummaryService service;

    @Test
    void consumerSeesMockedBusyIntervals() {
        Instant from = Instant.parse("2026-06-08T09:00:00Z");
        Instant to = Instant.parse("2026-06-08T17:00:00Z");

        when(calendarPort.freeBusy(anyLong(), eq(from), eq(to))).thenReturn(List.of(
                new BusyInterval(Instant.parse("2026-06-08T09:00:00Z"), Instant.parse("2026-06-08T10:00:00Z")),
                new BusyInterval(Instant.parse("2026-06-08T11:00:00Z"), Instant.parse("2026-06-08T11:30:00Z"))));

        long busyMinutes = service.busyMinutes(1L, from, to);

        assertEquals(90, busyMinutes);
    }
}
