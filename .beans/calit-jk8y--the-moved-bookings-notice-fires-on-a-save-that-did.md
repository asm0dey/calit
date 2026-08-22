---
# calit-jk8y
title: The moved-bookings notice fires on a save that did not change the calendar
status: completed
type: bug
priority: low
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-22T13:05:12Z
---

After saving a meeting type's write calendar, the page reports how many upcoming bookings stay on the calendar they were created on. It counts bookings whose stored calendar differs from the new target — so it also fires when the save did not change the target at all, if older bookings already sit elsewhere.

The statement is never false; it is just surprising, implying a move happened when nothing moved.

Found in the final whole-branch review of [[calit-bh5t]].

- [x] Compare the pre-save ref against the post-save one and only show the notice when the target actually changed

## Summary of Changes

Fixed both write-calendar save paths (`AdminResource.applyWriteCalendar` and `SharedMeetingsResource.applyWriteCalendar`) to compare the pre-save `CalendarRef` against the post-save one and skip the stay-behind count entirely when they are equal, since `CalendarRef` is a record with componentwise null-safe equals. The stay-behind notice now only renders when the write calendar genuinely changed, not merely because older bookings already sit elsewhere. In `SharedMeetingsResource`, the pre-save ref is read from whichever storage the current owner actually writes to (the type's own columns for the Creator, the co-host's `meeting_type_host` row otherwise), matching the existing write-branch split. Added regression tests to both `AdminWriteCalendarTest` and `SharedWriteCalendarTest` covering the no-op-resave and no-op-reclear cases plus a positive control that a real move still shows the notice.
