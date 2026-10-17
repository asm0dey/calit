package site.asm0dey.calit.calendar;

import module java.base;

/**
 * A busy block in absolute UTC time. Half-open [start, end).
 */
public record BusyInterval(Instant start, Instant end) {}
