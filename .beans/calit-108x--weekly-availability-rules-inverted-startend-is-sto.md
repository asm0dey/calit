---
# calit-108x
title: 'Weekly availability rules: inverted start/end is stored'
status: completed
type: bug
priority: normal
created_at: 2026-09-29T19:12:40Z
updated_at: 2026-09-30T09:16:54Z
---

AVAILABILITY_RULE has no DB CHECK (V1, V8) and two creation paths skip the order check that persistFrames (AdminResource.java:1628) applies:
- AdminResource.addTypeRule (:1414-1428), POST /me/meeting-types/{id}/availability
- AdminResource.createRule (:1550-1568)
Both persist LocalTime.parse(startTime/endTime) as-is, so end <= start is stored. A bad dayOfWeek (DayOfWeek.valueOf) throws IllegalArgumentException -> 500 (no mapper).

Found while checking docs/entity_model.md on simasch/calit docs/aiup-specs (line 166 claims end_time > start_time). Sibling of calit-wbtx (date override windows).

- [x] Reject/skip !end.isAfter(start) in addTypeRule and createRule (share one helper with persistFrames)
- [x] Handle invalid dayOfWeek as a form error, not a 500
- [-] Optional: migration adding CHECK (end_time > start_time) after repairing bad rows
- [x] Tests

## Summary of Changes

Extracted `AdminResource.persistFrame` from `persistFrames`; `addTypeRule` and `createRule` now go through it, so blank/unparseable/unknown-day/inverted/zero-length rules are skipped (no 500). DB CHECK migration skipped: would need a bad-row repair and every write path is now guarded. Test in AdminAvailabilityTest.
