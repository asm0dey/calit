package com.calit.web;

import java.util.List;

/** One connected Google account section on the /me/google page. */
public record AccountView(Long credentialId, String accountEmail, boolean needsReconnect,
                          List<CalendarRow> calendars, boolean holdsWriteTarget) {}
