package site.asm0dey.calit.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

/**
 * Guards the N+1 fix in {@link SlotService#generateRawSlots}: the number of SQL statements it
 * issues must be a small constant, independent of the booking-horizon length. Before the fix
 * the per-day loop issued ~3 queries per day (~90 over 30 days, ~180 over 60); after the fix it
 * batch-loads rules, overrides and override windows once (<= 6 statements total).
 */
@QuarkusTest
class SlotServiceQueryCountTest {

    @Inject
    SlotService slotService;

    @Inject
    EntityManagerFactory emf;

    private static final LocalDate FROM = LocalDate.of(2026, 6, 8); // Monday

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @TestTransaction
    void queryCountIsConstantInHorizonLength() {
        seedSettings("Europe/Amsterdam");
        MeetingType t = meetingType("qc-type", 60);
        // A global weekly rule for every weekday, plus one date override with a window.
        for (DayOfWeek dow : DayOfWeek.values()) {
            globalRule(dow, "09:00", "11:00");
        }
        DateOverride o = override(t.id, FROM.plusDays(2));
        o.persist();
        window(o, "13:00", "14:00");

        Statistics statistics = stats();

        // 30-day horizon.
        statistics.clear();
        slotService.generateRawSlots(t, FROM, FROM.plusDays(30));
        long count30 = statistics.getPrepareStatementCount();

        // 60-day horizon — must issue the SAME number of statements (constant, not linear).
        statistics.clear();
        slotService.generateRawSlots(t, FROM, FROM.plusDays(60));
        long count60 = statistics.getPrepareStatementCount();

        assertTrue(count30 <= 6, "slot generation must be a small constant number of queries, was " + count30);
        assertEquals(
                count30,
                count60,
                "query count must be constant in the horizon (30d=" + count30 + ", 60d=" + count60 + ")");
    }

    // --- helpers ---

    private void seedSettings(String zone) {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = zone;
        s.persist();
    }

    private MeetingType meetingType(String slug, int minutes) {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = slug;
        t.slug = slug;
        t.durationMinutes = minutes;
        t.persist();
        return t;
    }

    private void globalRule(DayOfWeek dow, String start, String end) {
        AvailabilityRule r = new AvailabilityRule();
        r.ownerId = 1L;
        r.dayOfWeek = dow;
        r.startTime = LocalTime.parse(start);
        r.endTime = LocalTime.parse(end);
        r.meetingTypeId = null;
        r.persist();
    }

    private DateOverride override(Long meetingTypeId, LocalDate date) {
        DateOverride o = new DateOverride();
        o.ownerId = 1L;
        o.meetingTypeId = meetingTypeId;
        o.overrideDate = date;
        return o;
    }

    private void window(DateOverride parent, String start, String end) {
        DateOverrideWindow w = new DateOverrideWindow();
        w.dateOverrideId = parent.id;
        w.startTime = LocalTime.parse(start);
        w.endTime = LocalTime.parse(end);
        w.persist();
    }
}
