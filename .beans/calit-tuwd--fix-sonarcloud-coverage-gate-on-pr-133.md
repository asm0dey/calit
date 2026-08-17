---
# calit-tuwd
title: 'Fix SonarCloud coverage gate on PR #133'
status: in-progress
type: task
priority: normal
created_at: 2026-08-17T09:01:52Z
updated_at: 2026-08-17T09:35:56Z
---

Add test-only coverage for GoogleCalendarPort.updateEvent/updateEventDetails, writeAddress fallback branches, group-details null guard, and declineGuest stored-ref path. No production code changes.


## Todo
- [x] Read StoredCalendarAddressTest.java, DeleteEventAlreadyGoneTest.java
- [x] Read GoogleCalendarPort.java writeAddress/updateEvent/updateEventDetails
- [x] Add updateEvent/updateEventDetails port tests (stored ref -> patch to ref's calendar)
- [x] Add writeAddress fallback branch tests (null ref, foreign-owner credential, missing pieces, deleted credential)
- [x] RED evidence for port tests
- [x] Read GroupEditDetailsTest.java, BookingService.java group-details site
- [x] Tighten GroupEditDetailsTest: stub createEvent to return real CalendarRef, eq(ref) instead of any()
- [x] Add null-eventRow group booking test (covers :1037 eventRow!=null guard)
- [x] RED evidence for group-details tests
- [x] Read BookingServiceGuestTest.java / BookingCalendarAddressTest.java, declineGuest call site
- [x] Add declineGuest stored-ref test
- [x] RED evidence for declineGuest test
- [x] Run targeted test suite
- [x] Run spotless:apply
- [x] Run full suite (842 tests, 0 failures)
- [x] Confirm git diff e51fb13 -- src/main is empty
- [ ] Commit
- [ ] Write report file
