---
# calit-z8ii
title: PrivacyService.anonymise and erasure boundary report
status: completed
type: task
priority: normal
created_at: 2026-09-16T14:00:09Z
updated_at: 2026-09-16T14:06:11Z
parent: calit-l3fk
---

Task 4: PrivacyService.anonymise, PrivacyService.eraseByManageToken, ErasureReport, PrivacyConfig


## Summary of Changes

- Created `PrivacyService` (`anonymise(Long)`, `eraseByManageToken(String)`), `ErasureReport`
  record + `GoogleOutcome` enum, `PrivacyConfig` (`inviteeErasureEnabled()`,
  `bookingRetentionDays()`) under `site.asm0dey.calit.privacy`.
- Added `calit.privacy.invitee-erasure` / `calit.retention.booking-days` to
  `application.properties` and `.env.example`, matching the brief verbatim.
- TDD: wrote `BookingErasureTest` first (RED: compile error, PrivacyService/ErasureReport did not
  exist), then implemented (GREEN: 4/4 passing).
- Adaptation: the brief's test helper `firstMeetingTypeId()` assumed a MeetingType was pre-seeded
  for owner 1. DatabaseResetCallback only seeds the admin app_user row, so the helper now
  seeds a minimal MeetingType on demand when none exists.
- Full suite: `mvn test` -> 1162 tests, 0 failures, 0 errors, BUILD SUCCESS.
