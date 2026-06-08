package com.calit.booking;

import java.time.Instant;
import java.util.List;

/** Half-open instant interval [start, end). Touching boundaries do not overlap. */
public record Interval(Instant start, Instant end) {

    public boolean overlaps(Interval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean overlapsAny(List<Interval> busy) {
        for (Interval b : busy) {
            if (overlaps(b)) {
                return true;
            }
        }
        return false;
    }
}
