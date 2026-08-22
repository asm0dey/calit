---
# calit-x921
title: Drop the now-dead daysOfWeek template parameter
status: todo
type: task
priority: low
created_at: 2026-08-22T11:26:11Z
updated_at: 2026-08-22T11:26:38Z
---

Since the workplan grid moved into the shared `AdminResource/_workplanGrid.html` fragment, every page passes `List<WeekRow> week` and nothing reads `DayOfWeek[] daysOfWeek` any more. `AdminResource.meetingTypes(...)` now declares both — two parameters that read as the same concept, one of them dead, on a signature already carrying `@SuppressWarnings("java:S107")` for its parameter count.

Raised as a Minor by the review of PR #147 and deliberately left there: dropping it cleanly spans four template declarations and three `Templates` method signatures, which is wider than that PR's scope.

## Notes

- Check each template's `{@...}` parameter declaration as well as the Java `Templates` signature — Qute fails at build time on a declared-but-missing parameter, not at runtime, so the compiler and the template checker together should catch anything missed.
- Grep for `daysOfWeek` across `src/main` before and after; expect zero hits after.
- Pure dead-code removal — no behaviour change, no new tests, the existing suite is the regression net.

## Todo

- [ ] Remove the daysOfWeek parameter from the template declarations that no longer use it
- [ ] Remove it from the matching Templates signatures and call sites in AdminResource
- [ ] `mvn test` green
