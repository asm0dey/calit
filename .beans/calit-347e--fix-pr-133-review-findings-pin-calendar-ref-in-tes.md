---
# calit-347e
title: 'Fix PR #133 review findings: pin calendar ref in tests'
status: completed
type: task
priority: normal
created_at: 2026-08-17T08:28:00Z
updated_at: 2026-08-17T08:34:16Z
---

Add tests for Finding 1 (update/updateDetails ref pinning) and Finding 2 (multi-account credential resolution) from final whole-branch review of PR #133 branch feat/per-booking-calendar-address. Test-only changes.



## Summary of Changes

Added 3 tests fixing 2 Important findings from PR #133 final review, test-only:

- BookingCalendarAddressTest: rescheduleOfANonApprovalTypePatchesTheStoredCalendar (guards updateEvent ref at BookingService reschedule auto-confirm path), updateDetailsPatchesTheStoredCalendar (guards updateEventDetails ref at updateDetails).
- StoredCalendarAddressTest: deletesUsingTheStoredRefsCredentialNotTheWriteTargetsCredential (guards writeAddress resolving the ref's own credential, not the write-target's, in the multi-account case).

All 3 proven RED against a temporarily-broken production line, then GREEN after revert; git diff 3c6e139 -- src/main empty throughout. Full covering set (44 tests across 8 classes) green. Report at .superpowers/sdd/rma2-final-review-fix-report.md.
