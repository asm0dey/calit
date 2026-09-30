---
# calit-idc9
title: 'Meeting type notice/horizon/buffers/cadence: no server-side limits, bad cadence 500s'
status: completed
type: bug
priority: normal
created_at: 2026-09-29T18:51:53Z
updated_at: 2026-09-30T09:20:49Z
---

Spec: UC-010 BR-009, A10. AdminResource.applyEditableFields (:617-667) checks only duration <= 0; buffers, minNotice, horizon stored as-is (negatives ok). Cadence Integer.valueOf (:652-654) throws NumberFormatException, uncaught by callers (catch IllegalStateException only) -> 500; zero/negative cadence stored. No DB CHECKs on meeting_type. Reuse parseNonNegative / parseNonNegativeIntOrNull patterns.

- [x] Reject negatives via HostRuleException (renders as form error); cadence must parse and be > 0
- [x] Decide upper bounds
- [x] Tests incl. non-numeric cadence

## Summary of Changes

`applyEditableFields` range-checks via `inRange` -> `HostRuleException(adm_detail_error_range)` (form error, de/he): buffers 0..1440, min notice 0..525600 (365 days), horizon 0..730 days, cadence 1..1440; non-numeric cadence is refused, not a 500. Bounds are constants in AdminResource. Test in AdminDurationGuardTest.
