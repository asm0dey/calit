package site.asm0dey.calit.google;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Example downstream consumer of CalendarPort. Demonstrates the mockable seam:
 * Plans 3 & 4 inject CalendarPort the same way and mock it in tests.
 */
@ApplicationScoped
public class BusySummaryService {

    private final CalendarPort calendarPort;

    @Inject
    public BusySummaryService(CalendarPort calendarPort) {
        this.calendarPort = calendarPort;
    }

    /** Total busy minutes in [from, to), using the port's already-merged intervals. */
    public long busyMinutes(Long ownerId, Instant from, Instant to) {
        List<BusyInterval> busy = calendarPort.freeBusy(ownerId, from, to);
        long total = 0;
        for (BusyInterval b : busy) {
            total += Duration.between(b.start(), b.end()).toMinutes();
        }
        return total;
    }
}
