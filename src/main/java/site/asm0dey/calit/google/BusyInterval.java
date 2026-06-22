package site.asm0dey.calit.google;

import java.time.Instant;

/** A busy block in absolute UTC time. Half-open [start, end). */
public record BusyInterval(Instant start, Instant end) {}
