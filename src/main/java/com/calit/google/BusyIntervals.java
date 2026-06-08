package com.calit.google;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure interval algebra for busy time. No Google or CDI dependencies — unit-tested directly.
 */
public final class BusyIntervals {

    private BusyIntervals() {
    }

    /**
     * Sort the given intervals by start, then collapse any that overlap or merely touch
     * (end == next.start) into single intervals. Returns a new immutable-friendly list;
     * the input is never mutated.
     */
    public static List<BusyInterval> merge(List<BusyInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<BusyInterval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(BusyInterval::start).thenComparing(BusyInterval::end));

        List<BusyInterval> merged = new ArrayList<>();
        Instant curStart = sorted.getFirst().start();
        Instant curEnd = sorted.getFirst().end();

        for (int i = 1; i < sorted.size(); i++) {
            BusyInterval next = sorted.get(i);
            if (!next.start().isAfter(curEnd)) {
                // Overlapping or touching: extend the current block.
                if (next.end().isAfter(curEnd)) {
                    curEnd = next.end();
                }
            } else {
                merged.add(new BusyInterval(curStart, curEnd));
                curStart = next.start();
                curEnd = next.end();
            }
        }
        merged.add(new BusyInterval(curStart, curEnd));
        return merged;
    }
}
