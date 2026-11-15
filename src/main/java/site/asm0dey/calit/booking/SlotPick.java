package site.asm0dey.calit.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One chosen session of a multi-slot booking: start instant plus length in minutes.
 */
public record SlotPick(Instant startUtc, int minutes) {
    static final int MAX_PICKS = 5;
    // Far beyond any booking horizon (max 730 days), yet keeps endUtc()/atZone() clear of overflow.
    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Duration LATEST_AHEAD = Duration.ofDays(3650);

    public Instant endUtc() {
        return startUtc.plusSeconds(minutes * 60L);
    }

    /**
     * Wire form {@code <ISO instant>/<minutes>}.
     */
    public String value() {
        return startUtc + "/" + minutes;
    }

    /**
     * Parses, trims, drops duplicates (order kept) and sorts by start. A missing parameter (null) is no picks.
     */
    public static List<SlotPick> parseAll(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        var distinct = new LinkedHashSet<SlotPick>();
        for (var s : raw) {
            distinct.add(parse(s));
        }
        if (distinct.size() > MAX_PICKS) {
            throw new BookingValidationException("Pick at most " + MAX_PICKS + " sessions.");
        }
        return distinct.stream().sorted(Comparator.comparing(SlotPick::startUtc)).toList();
    }

    private static SlotPick parse(String s) {
        try {
            var v = s.trim();
            var i = v.lastIndexOf('/');
            var minutes = Integer.parseInt(v.substring(i + 1));
            if (i < 0 || minutes <= 0) {
                throw new IllegalArgumentException();
            }
            var start = Instant.parse(v.substring(0, i));
            if (start.isBefore(EARLIEST) || start.isAfter(Instant.now().plus(LATEST_AHEAD))) {
                throw new IllegalArgumentException();
            }
            return new SlotPick(start, minutes);
        } catch (RuntimeException e) {
            throw new BookingValidationException("Invalid session: " + s);
        }
    }
}
