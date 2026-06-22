package site.asm0dey.calit.booking.events;

import java.time.Instant;

public record BookingRescheduled(Long bookingId, Instant oldStartUtc) {}
