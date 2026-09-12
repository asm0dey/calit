package site.asm0dey.calit.email;

import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.OwnerSettings;

/** A group booking's per-host delivery target: that host's own settings + own booking row. */
public record HostDelivery(OwnerSettings settings, Booking booking) {}
