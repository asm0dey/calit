package site.asm0dey.calit.domain;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DateOverrideTest {

    private static final LocalDate D = LocalDate.of(2026, 7, 1);

    @Test
    @TestTransaction
    void resolveReturnsNullWhenNoOverride() {
        assertNull(DateOverride.resolve(1L, 123_456L, D));
    }

    @Test
    @TestTransaction
    void perTypeOverrideWinsOverGlobal() {
        // FK columns reference real rows: persist a real MeetingType for the per-type override.
        MeetingType type = new MeetingType();
        type.ownerId = 1L;
        type.name = "Override Type";
        type.slug = "do-pertype-wins";
        type.durationMinutes = 30;
        type.persist();

        // Global override for the date (09:00-10:00).
        DateOverride global = override(null, D);
        global.persist();
        window(global, "09:00", "10:00");

        // Per-type override for the same date (13:00-14:00) — should win.
        DateOverride typed = override(type.id, D);
        typed.persist();
        window(typed, "13:00", "14:00");

        DateOverride resolved = DateOverride.resolve(1L, type.id, D);
        assertEquals(typed.id, resolved.id);
        assertEquals(1, resolved.windows.size());
        assertEquals(LocalTime.of(13, 0), resolved.windows.getFirst().startTime);
    }

    @Test
    @TestTransaction
    void globalOverrideResolvesWhenNoPerTypeExists() {
        DateOverride global = override(null, D);
        global.persist();
        window(global, "08:00", "09:00");

        // A meeting type with no per-type override falls through to the global one.
        DateOverride resolved = DateOverride.resolve(1L, 987_654L, D);
        assertEquals(global.id, resolved.id);
        assertEquals(LocalTime.of(8, 0), resolved.windows.getFirst().startTime);
    }

    @Test
    @TestTransaction
    void emptyWindowsOverrideResolvesAsDayOff() {
        DateOverride dayOff = override(null, D);
        dayOff.persist(); // no windows added

        DateOverride resolved = DateOverride.resolve(1L, 555L, D);
        assertEquals(dayOff.id, resolved.id);
        assertTrue(resolved.windows.isEmpty()); // empty = day off (caller blocks the day)
    }

    @Test
    @TestTransaction
    void windowsLoadInStartTimeOrder() {
        DateOverride o = override(null, D);
        o.persist();
        // Insert out of order; expect ordered load.
        window(o, "14:00", "15:00");
        window(o, "09:00", "10:00");

        DateOverride resolved = DateOverride.resolve(1L, 42L, D);
        assertEquals(2, resolved.windows.size());
        assertEquals(LocalTime.of(9, 0), resolved.windows.getFirst().startTime);
        assertEquals(LocalTime.of(14, 0), resolved.windows.get(1).startTime);
    }

    // --- helpers ---

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
