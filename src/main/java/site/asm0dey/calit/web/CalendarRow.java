package site.asm0dey.calit.web;

/**
 * One calendar row rendered in an account section on the /me/calendars page.
 */
public record CalendarRow(Long accountId, String remoteId, String summary, boolean readForBusy, boolean writeTarget) {}
