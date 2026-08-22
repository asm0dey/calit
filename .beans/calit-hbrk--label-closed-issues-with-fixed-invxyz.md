---
# calit-hbrk
title: Label closed issues with fixed-in:vX.Y.Z
status: completed
type: task
priority: normal
created_at: 2026-08-22T16:16:12Z
updated_at: 2026-08-22T16:17:27Z
---

After 1.21.0 publishes, walk the closed GitHub issues and apply the existing `fixed-in:vX.Y.Z` label convention (green #0e8a16, description "Fix first available in release vX.Y.Z") to any that lack one.

Unlabelled at the time of writing: #140, #129, #127, #120.

- [x] Waited for the v1.21.0 release to publish
- [x] Resolved each unlabelled issue to the commit that fixed it
- [x] Mapped each commit to the first tag containing it
- [x] Created the two missing `fixed-in:` labels
- [x] Applied labels

## Summary of Changes

Every closed issue that has a release behind it now carries a `fixed-in:` label.

- **#127** (booking page ignored per-type working hours) -> `fixed-in:v1.20.2`. Fixed by dd79264 via PR #134; `git tag --contains` puts v1.20.2 first.
- **#120** (workplan grid on the meeting-type create form) -> `fixed-in:v1.21.0`. Fixed by PR #147, merge 16fb6e2, first tagged in v1.21.0.
- Created labels `fixed-in:v1.20.2` and `fixed-in:v1.21.0` to match the existing convention (green #0e8a16, "Fix first available in release vX.Y.Z").

Two closed issues were deliberately left unlabelled:

- **#140** "Force sync Google Calendar" — a feature request the reporter withdrew ("Nevermind, it already is!"). No code shipped for it, so no release fixed it.
- **#129** — an empty issue (whitespace title, no body), opened by accident. Nothing to attribute.

All other closed issues (#118, #116, #99, #98, #81, #75) were already labelled and were left alone.
