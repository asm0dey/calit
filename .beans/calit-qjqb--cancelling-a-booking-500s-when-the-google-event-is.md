---
# calit-qjqb
title: Cancelling a booking 500s when the Google event is already deleted (410 Gone)
status: completed
type: bug
priority: high
created_at: 2026-08-15T22:59:58Z
updated_at: 2026-08-16T08:41:14Z
---

GitHub issue: https://github.com/asm0dey/calit/issues/118

Deleting the event directly in Google Calendar does not sync back to calit. Then cancelling that booking in calit (`POST /me/bookings/{id}/cancel`) blows up with a 500:

```
java.io.UncheckedIOException: deleteEvent failed
  at site.asm0dey.calit.google.GoogleCalendarPort.deleteEvent(GoogleCalendarPort.java:288)
  at site.asm0dey.calit.booking.BookingService.cancelSingle(BookingService.java:1175)
  at site.asm0dey.calit.web.AdminResource.ownerCancel(AdminResource.java:1352)
Caused by: com.google.api.client.googleapis.json.GoogleJsonResponseException: 410 Gone
  DELETE https://www.googleapis.com/calendar/v3/calendars/.../events/...?sendUpdates=all
  { "code": 410, "reason": "deleted", "message": "Resource has been deleted" }
```

`GoogleCalendarPort.deleteEvent` wraps every `IOException` into `UncheckedIOException`, so a 410 (event already gone) — and arguably 404 — aborts the whole cancel transaction even though the desired end state (no event in Google) already holds.

Deleting an already-deleted event is idempotent from the caller's point of view: treat 410/404 as success, log at DEBUG/INFO, let the local cancellation proceed. Other statuses must keep failing loudly.

## Todo

- [x] `GoogleCalendarPort.deleteEvent`: catch `GoogleJsonResponseException` with status 410 or 404 and return normally (log it); rethrow everything else as today
- [x] Check the sibling write paths (`updateEventDetails`, move/patch) for the same already-gone hazard — at minimum note the decision in the bean
- [x] Test: cancel succeeds when the calendar port reports the event is already gone (fake/stub `CalendarPort`, no live Google needed)
- [x] Verify the booking still ends up cancelled locally and the cancellation email/.ics still goes out

## Decision: sibling write paths keep failing loudly

updateEvent / updateEventDetails (GoogleCalendarPort, both `events().patch(...)`) deliberately do NOT get the 410/404 tolerance. Delete is idempotent — a 410 means the end state we wanted (no event on Google) already holds. A patch is not: a 410 means the new time/summary/attendees were applied nowhere, so swallowing it would report a reschedule that never happened. If reschedule-onto-a-deleted-event turns out to hurt users, the fix is to re-create the event, not to ignore the error — separate bean.

## Summary of Changes

GoogleCalendarPort.deleteEvent now catches GoogleJsonResponseException ahead of the generic IOException catch: status 410 or 404 logs at INFO and returns (the event is already gone on Google, so the delete is idempotent); every other status still throws UncheckedIOException("deleteEvent failed") as before. Fixing it in the port covers both BookingService.cancelSingle and deleteGroupGoogleEvent with no service-side change, so the booking still goes CANCELLED locally and the cancellation email/.ics still goes out (covered by RescheduleCancelTest / GroupCancelRescheduleTest).

New test: DeleteEventAlreadyGoneTest — 410 and 404 do not throw, 500 and a plain IOException still do.
