package site.asm0dey.calit.web;

import module java.base;

/**
 * One connected Google account section on the /me/google page.
 */
public record AccountView(
        Long credentialId,
        String accountEmail,
        boolean needsReconnect,
        boolean loadFailed,
        List<CalendarRow> calendars,
        boolean holdsWriteTarget
) {}
