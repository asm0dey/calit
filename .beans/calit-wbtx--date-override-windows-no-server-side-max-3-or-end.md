---
# calit-wbtx
title: 'Date override windows: no server-side max-3 or end-after-start check'
status: completed
type: bug
priority: normal
created_at: 2026-09-29T18:51:40Z
updated_at: 2026-09-30T09:16:54Z
---

Spec: UC-011 A5. AdminResource.persistWindows (web/AdminResource.java:763-785) stores any number of windows and inverted ones (persistFrames :1625 drops inverted). Callers: :754, :1484, :2083. SharedMeetingsResource.java:407-422 duplicates the loop with the same gaps. No DB CHECK (V2).

- [x] Drop/reject windows with !end.isAfter(start); cap at 3
- [x] Route SharedMeetingsResource through the same helper
- [-] Optional: new migration with CHECK (end_time > start_time)
- [x] Tests

## Summary of Changes

`AdminResource.persistWindows` (now static, shared with SharedMeetingsResource) drops zero-length/inverted windows and keeps at most `MAX_OVERRIDE_WINDOWS` = 3 (the forms render three rows). DB CHECK skipped, same reason as calit-108x. Test in AdminDateOverridesTest.
