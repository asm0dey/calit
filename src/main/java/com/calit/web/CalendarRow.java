package com.calit.web;

/** One calendar row rendered in an account section on the /me/google page. */
public record CalendarRow(Long credentialId, String googleCalendarId, String summary,
                          boolean readForBusy, boolean writeTarget) {}
