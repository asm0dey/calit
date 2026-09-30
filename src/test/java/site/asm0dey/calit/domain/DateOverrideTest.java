package site.asm0dey.calit.domain;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DateOverrideTest {
    private static final LocalDate D = LocalDate.of(2026, 7, 1);

    @Test
    @TestTransaction
    void resolveReturnsNullWhenNoOverride() {
        assertThat(DateOverride.resolve(1L, 123_456L, D)).isNull();
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
        assertThat(resolved.id).isEqualTo(typed.id);
        assertThat(resolved.windows).hasSize(1);
        assertThat(resolved.windows.getFirst().startTime).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    @TestTransaction
    void globalOverrideResolvesWhenNoPerTypeExists() {
        DateOverride global = override(null, D);
        global.persist();
        window(global, "08:00", "09:00");
        // A meeting type with no per-type override falls through to the global one.
        DateOverride resolved = DateOverride.resolve(1L, 987_654L, D);
        assertThat(resolved.id).isEqualTo(global.id);
        assertThat(resolved.windows.getFirst().startTime).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    @TestTransaction
    void emptyWindowsOverrideResolvesAsDayOff() {
        DateOverride dayOff = override(null, D);
        // no windows added
        dayOff.persist();

        DateOverride resolved = DateOverride.resolve(1L, 555L, D);
        assertThat(resolved.id).isEqualTo(dayOff.id);
        // empty = day off (caller blocks the day)
        assertThat(resolved.windows).isEmpty();
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
        assertThat(resolved.windows).hasSize(2);
        assertThat(resolved.windows.getFirst().startTime).isEqualTo(LocalTime.of(9, 0));
        assertThat(resolved.windows.get(1).startTime).isEqualTo(LocalTime.of(14, 0));
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
