---
# calit-3oc8
title: Merge @TestProfile classes by config conflicts to cut Quarkus test boots
status: completed
type: task
priority: normal
created_at: 2026-11-09T18:37:10Z
updated_at: 2026-11-09T18:55:06Z
---

13 test profiles + default = 14 Quarkus boots per suite (~118s of 258s in classes absorbing a boot). Group compatible profiles by actual config conflicts; aim 3-4 boots.

- [x] Read profile tests and map conflicts
- [x] Merge compatible profiles
- [x] Full suite green; compare timing before/after

## Summary of Changes

Grouped 13 profiles into 3 by config conflicts: CommonFeaturesProfile (+policy override), new IsolatedSettingsProfile and OidcCsrfProfile. Full suite on an isolated Postgres: 339s/16 boots before, 215s/6 boots after, 1464 tests green both times.
