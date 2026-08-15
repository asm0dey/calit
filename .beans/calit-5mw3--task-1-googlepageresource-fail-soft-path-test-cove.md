---
# calit-5mw3
title: 'Task 1: GooglePageResource fail-soft path test coverage'
status: completed
type: task
priority: normal
created_at: 2026-08-15T19:39:52Z
updated_at: 2026-08-15T19:41:04Z
---

Add tests for GooglePageResource fail-soft path when Google is unreachable (WARN calls at :109 and :153). Test-only change per task-1-brief.md.



## Summary of Changes
Added two tests to GooglePageResourceTest.java covering the fail-soft path when Google is unreachable:
- getShowsBannerAndKeepsSavedRowsWhenGoogleUnreachable: GET /me/google renders the error banner and keeps saved calendar rows visible instead of wiping them when calendarListPort throws.
- savePreservesSelectionWhenGoogleUnreachableMidSave: POST /me/google/calendars preserves the existing write target and read calendars from the DB when Google becomes unreachable mid-save.

Both tests passed on first run (no production code change needed, as expected — GooglePageResource.java already implements this behavior at lines 103-113 and 145-155). Full test class: 8/8 passing.
