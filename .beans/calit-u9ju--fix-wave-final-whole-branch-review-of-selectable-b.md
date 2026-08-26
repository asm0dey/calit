---
# calit-u9ju
title: 'Fix wave: final whole-branch review of selectable-booking-duration'
status: completed
type: task
priority: normal
created_at: 2026-08-26T00:24:21Z
updated_at: 2026-08-26T00:24:31Z
parent: calit-p5xm
---

Apply fixes for the four Important findings from the final whole-branch review of branch selectable-booking-duration, plus listed hygiene cleanup and new tests.


## Todo
- [x] Fix 1: owner-side reschedule grid drawn at booking's own length (AdminResource.daySlots)
- [x] Fix 2: de-dupe duplicate durations in saveDurations (no more 500 on repeat)
- [x] Fix 3: zero-buffer round trip (meetingTypeDetail.html + sharedAvailability.html twin)
- [x] Fix 4: batch allowedDurations on the landing page (N+1 removed)
- [x] Hygiene: delete dead assertSlotAvailable overloads, effectiveBuffer 2-arg overloads, private assertDurationAllowed
- [x] Hygiene: SlotService missing ORDER BY on rules query
- [x] Hygiene: meetingTypeDetail.html buffer column labels use adm_detail_* keys
- [x] Test: cross-timezone availableSlots end-to-end (London/Berlin), confirmed red when latticeZone forced null
- [x] Test: cross-owner isolation for POST /me/meeting-types/{id}/durations
- [x] Test: duplicate duration regression + zero-buffer save/render/save round trip
- [x] bun run format + spotless:check clean
- [x] Full suite green: 975 tests, 0 failures, 0 errors

## Summary of Changes
See .superpowers/sdd/2026-08-25-selectable-booking-duration/final-fix-report.md for the full writeup.
Fixed all four Important findings from the final whole-branch review, folded in the listed
hygiene cleanup (dead code deletion, missing ORDER BY, mislabeled i18n keys), and added the
requested tests including a manually-confirmed red/green cycle for the cross-timezone
availableSlots regression test.
