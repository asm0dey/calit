---
# calit-2pue
title: 'Task 9: retention scheduler'
status: completed
type: task
created_at: 2026-09-16T17:14:46Z
updated_at: 2026-09-16T17:14:46Z
parent: calit-l3fk
---

Per-owner/instance-default retention sweep that anonymises past bookings via PrivacyService.anonymise(Collection)

## Summary of Changes

- Added `OwnerSettings.retentionDaysOrDefault(Integer instanceDefault)`: owner's own window wins in
  both directions over the instance default.
- Added `RetentionScheduler` (`scheduler/RetentionScheduler.java`): daily `@Scheduled(cron=...)`
  tick, matching `ReminderScheduler`'s FOR UPDATE SKIP LOCKED / no-leader pattern. Per R13, one tick
  is ONE transaction: `SELECT b.id ... JOIN owner_settings ... FOR UPDATE OF b SKIP LOCKED LIMIT 200`
  claims up to 200 due booking ids, then `privacy.anonymise(ids)` (REQUIRED propagation) runs in
  that SAME transaction — a set-based erasure, never a per-id loop.
- Settings UI: `bookingRetentionDays` number input in `settings.html`, parsed in
  `AdminResource.updateSettings` (blank/zero/negative/unparseable -> null = no override), two new
  `AdminMessages` keys with German translations, both added to `HE_DEFERRED_ADMIN_KEYS` in
  `MultiHostMessageParityTest` (Hebrew deferred per Task 12, per the epic's standing i18n rule).
- Test fixtures: added `ErasureFixtures.seedUpcomingBookingId()` (future CONFIRMED booking, shares
  `SEED_OFFSET` with `seedPastBookingId()` to avoid `booking_no_overlap_held`).
- Tests: `RetentionSchedulerTest` (top-level, no instance default) — unset default is a no-op,
  per-owner override applies without an instance default, a future booking is never touched, and a
  second sweep leaves an already-erased booking's `erased_at` unchanged (idempotency, beyond the
  brief). `RetentionSchedulerInstanceDefaultTest` (top-level, separate class per R3 — a
  `@Nested @TestProfile` inner class would not run under surefire/Quarkus restart semantics) —
  instance default catches an old booking, a longer owner override wins over the instance default.
- Did not add a "no owner_settings row" test: every account-creation path
  (`SetupResource`/`SignupResource`/`UsersResource`/`GoogleSignInService`/`OidcSignInService`/
  `MeSetupResource`) seeds `OwnerSettings` via `OwnerSettings.seed(...)`, so a booking's owner
  having no settings row cannot occur through any real path — noted per the task's own escape
  hatch rather than fabricated.
- Full suite: `mvn test` -> 1202 tests, 0 failures, 0 errors, BUILD SUCCESS.
