---
# calit-3aen
title: Workplan day actions sit on the day-name axis, gap grows with window width
status: completed
type: bug
priority: normal
created_at: 2026-09-10T22:14:51Z
updated_at: 2026-09-11T05:15:47Z
---

GH #197. In _workplanGrid.html the per-day action buttons share a justify-between row with the day label, so they align with the day name instead of the time frames they act on, and all slack lands between the two children (gap scales with viewport). Plan: docs/superpowers/plans/2026-09-11-workplan-day-actions-alignment.md

## Summary of Changes

_workplanGrid.html: day label moved to its own line; [data-frames] and the
action-button group are now siblings in a single flex-wrap gap-x-4 row, frames
first. No JS, CSS or Java change -- workplan.js only uses descendant selectors.
New AdminWorkplanLayoutTest pins document order (actions after frames) and the
absence of justify-between inside a day card.
