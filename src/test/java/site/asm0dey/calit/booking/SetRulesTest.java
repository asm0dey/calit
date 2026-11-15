package site.asm0dey.calit.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.SetRules.Blocked;
import site.asm0dey.calit.domain.MeetingType;

class SetRulesTest {
    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private static MeetingType type(int min, int max, boolean sameDay) {
        var t = new MeetingType();
        t.multiSlotMin = min;
        t.multiSlotMax = max;
        t.multiSlotSameDay = sameDay;
        return t;
    }

    private static SlotPick pick(String iso, int minutes) {
        return new SlotPick(Instant.parse(iso), minutes);
    }

    @Test
    void parsesAndSortsPicks() {
        var picks = SlotPick.parseAll(List.of(" 2026-01-02T10:00:00Z/30 ", "2026-01-01T10:00:00Z/60"));
        assertThat(picks).extracting(SlotPick::value).containsExactly(
                "2026-01-01T10:00:00Z/60",
                "2026-01-02T10:00:00Z/30"
        );
    }

    @Test
    void dropsDuplicatePick() {
        assertThat(SlotPick.parseAll(List.of("2026-01-01T10:00:00Z/30", "2026-01-01T10:00:00Z/30"))).hasSize(1);
    }

    @Test
    void rejectsMalformedPick() {
        for (var bad : List.of("x/30", "2026-01-01T10:00:00Z/-5", "2026-01-01T10:00:00Z")) {
            var raw = List.of(bad);
            assertThatThrownBy(() -> SlotPick.parseAll(raw)).isInstanceOf(BookingValidationException.class);
        }
    }

    @Test
    void rejectsSixPicks() {
        var raw = List
            .of("1", "2", "3", "4", "5", "6")
            .stream()
            .map(d -> "2026-01-0" + d + "T10:00:00Z/30")
            .toList();
        assertThatThrownBy(() -> SlotPick.parseAll(raw)).isInstanceOf(BookingValidationException.class);
    }

    @Test
    void blockedOverlap() {
        var t = type(1, 4, true);
        var picked = List.of(pick("2026-01-01T10:00:00Z", 60));
        assertThat(SetRules.blocked(t, picked, pick("2026-01-01T10:30:00Z", 30), AMS)).isEqualTo(Blocked.OVERLAP);
        assertThat(SetRules.blocked(t, picked, pick("2026-01-01T11:00:00Z", 30), AMS)).isEqualTo(Blocked.NONE);
        assertThat(SetRules.blocked(t, picked, picked.getFirst(), AMS)).isEqualTo(Blocked.NONE);
    }

    @Test
    void blockedSameDayInOwnerZone() {
        var picked = List.of(pick("2026-01-01T23:30:00Z", 30));
        // 23:30Z is 00:30 on Jan 2 in Amsterdam, so is 00:30Z of Jan 2 (01:30 local)
        assertThat(SetRules.blocked(type(1, 4, false), picked, pick("2026-01-02T00:30:00Z", 30), AMS))
            .isEqualTo(Blocked.SAME_DAY);
    }

    @Test
    void sameDayAllowedWhenToggleOn() {
        var picked = List.of(pick("2026-01-01T23:30:00Z", 30));
        assertThat(SetRules.blocked(type(1, 4, true), picked, pick("2026-01-02T00:30:00Z", 30), AMS)).isEqualTo(
                Blocked.NONE
        );
    }

    @Test
    void blockedMax() {
        var picked = List.of(pick("2026-01-01T10:00:00Z", 30), pick("2026-01-02T10:00:00Z", 30));
        assertThat(SetRules.blocked(type(1, 2, true), picked, pick("2026-01-03T10:00:00Z", 30), AMS)).isEqualTo(
                Blocked.MAX
        );
        var t = type(1, 2, true);
        t.multiSlotMax = null;
        assertThat(SetRules.blocked(t, List.of(picked.getFirst()), pick("2026-01-03T10:00:00Z", 30), AMS))
            .isEqualTo(Blocked.MAX);
    }

    @Test
    void validateCountBounds() {
        var t = type(2, 3, true);
        var one = List.of(pick("2026-01-01T10:00:00Z", 30));
        var allowed = List.of(30);
        assertThatThrownBy(() -> SetRules.validate(t, one, allowed, AMS))
            .isInstanceOf(BookingValidationException.class)
            .hasMessageContaining("between 2 and 3");
        var four = List.of(
                pick("2026-01-01T10:00:00Z", 30),
                pick("2026-01-02T10:00:00Z", 30),
                pick("2026-01-03T10:00:00Z", 30),
                pick("2026-01-04T10:00:00Z", 30)
        );
        assertThatThrownBy(() -> SetRules.validate(t, four, allowed, AMS)).isInstanceOf(
                BookingValidationException.class
        );
        SetRules.validate(t, four.subList(0, 2), List.of(30), AMS);
    }

    @Test
    void validateRejectsDisallowedDuration() {
        var t = type(1, 3, true);
        var picks = List.of(pick("2026-01-01T10:00:00Z", 45));
        var allowed = List.of(30, 60);
        assertThatThrownBy(() -> SetRules.validate(t, picks, allowed, AMS)).isInstanceOf(
                BookingValidationException.class
        );
    }

    @Test
    void validateRejectsOverlapAmongPicks() {
        var t = type(1, 3, true);
        var picks = List.of(pick("2026-01-01T10:00:00Z", 60), pick("2026-01-01T10:30:00Z", 30));
        var allowed = List.of(30, 60);
        assertThatThrownBy(() -> SetRules.validate(t, picks, allowed, AMS)).isInstanceOf(
                BookingValidationException.class
        );
    }

    @Test
    void rejectsPickOutsideSaneWindow() {
        for (var bad : List.of("+1000000000-12-31T23:59:59Z/30", "1999-12-31T23:59:59Z/30", "2100-01-01T00:00:00Z/30")) {
            var raw = List.of(bad);
            assertThatThrownBy(() -> SlotPick.parseAll(raw)).isInstanceOf(BookingValidationException.class);
        }
    }

    @Test
    void missingPicksParseToEmpty() {
        assertThat(SlotPick.parseAll(null)).isEmpty();
    }
}
