---
# calit-bwq3
title: Label closed GH issues with earliest release containing the fix
status: completed
type: task
priority: normal
created_at: 2026-08-16T12:09:04Z
updated_at: 2026-08-16T12:09:56Z
---

Add 'fixed-in:vX.Y.Z' labels to recently closed GitHub issues (#75, #81, #98, #99, #116, #118) mapping each to the earliest tagged release containing its fix.

- [x] Map each closed issue to its fix commit and first containing tag
- [x] Create fixed-in:* labels
- [x] Apply labels

## Summary of Changes

Created four green `fixed-in:vX.Y.Z` labels and applied them to the six recently closed GitHub issues:

| Issue | Fix commit / PR | Label |
|---|---|---|
| #118 delete of already-deleted Google event | PR #125 `5d60757` | fixed-in:v1.20.1 |
| #116 booking-page date/time format | PR #122 `41516f7` | fixed-in:v1.20.0 |
| #99 cannot create a booking | `6c79a4c` (seed OwnerSettings for /setup user) | fixed-in:v1.19.0 |
| #98 Google sync does not work | `de1fa46` (sync failure diagnostics) | fixed-in:v1.20.0 |
| #81 booking slot column layout | `9af92b3` | fixed-in:v1.16.0 |
| #75 drop @Transactional from resources | PR #82 `98e16f8` | fixed-in:v1.16.0 |

Mapping derived from `git tag --contains <fix-commit>` (earliest tag by creation date). No code changes.
