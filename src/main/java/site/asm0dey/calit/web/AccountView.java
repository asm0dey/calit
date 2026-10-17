package site.asm0dey.calit.web;

import module java.base;

/**
 * One Connected account section on the /me/calendars page; {@code provider} is the {@code CalendarProvider} name.
 */
public record AccountView(
        Long accountId,
        String label,
        String provider,
        boolean needsReconnect,
        boolean loadFailed,
        List<CalendarRow> calendars
) {}
