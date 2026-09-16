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
- Did not add a "no owner_settings row" test in the first pass: every account-creation path
  (`SetupResource`/`SignupResource`/`UsersResource`/`GoogleSignInService`/`OidcSignInService`/
  `MeSetupResource`) seeds `OwnerSettings` via `OwnerSettings.seed(...)`, so a booking's owner
  having no settings row cannot occur through any real path. Fix round 1 (R22) added the LEFT JOIN
  defensively anyway, and a test for it (see below).
- Full suite (first pass): `mvn test` -> 1202 tests, 0 failures, 0 errors, BUILD SUCCESS.

## Fix round 1 (task review R20-R22)

- **R20 — huge retention value crashes the whole sweep.** `parseRetentionDays` accepted any
  positive int with no ceiling; `now() - make_interval(days => 99999999)` raises "timestamp out of
  range" in Postgres, and since one tick is one transaction, that one poison window stopped
  retention for every owner. Fixed with a single constant, `PrivacyConfig.MAX_RETENTION_DAYS =
  36500` (~100 years), applied in three places: the settings input's `max="36500"`,
  `parseRetentionDays` (clamps down to the cap rather than rejecting), `PrivacyConfig
  .bookingRetentionDays()` (clamps the instance default the same way), and the sweep SQL, which now
  wraps the window in `LEAST(COALESCE(os.booking_retention_days, :instanceDefault), :cap)`.
- **R21 — the settings hint lied when an instance default is configured.** "Leave blank to keep
  bookings indefinitely" is false once an operator sets `calit.retention.booking-days` — blank
  falls back to that default instead. `AdminResource` now passes the effective instance default
  (`privacyConfig.bookingRetentionDays().orElse(null)`) to the settings template as a new
  `retentionInstanceDefault` param, and the template picks one of two full-sentence messages:
  `adm_settings_retention_hint_with_default(int days)` or `adm_settings_retention_hint_forever()`
  — both end in the same "what gets removed" sentence, kept whole per message (not concatenated in
  the template) so translations stay grammatical. German added for both; the old single
  `adm_settings_retention_hint` key is gone everywhere (bundle interface, both `.properties` files,
  and `HE_DEFERRED_ADMIN_KEYS`, which now lists the two new keys instead).
- **R22 (promoted minors), all addressed:**
  - `RetentionScheduler`'s sweep SQL now uses `LEFT JOIN owner_settings` (was `JOIN`), so a booking
    whose owner has no settings row still falls back to the instance default instead of being
    silently excluded; `FOR UPDATE OF b` already scoped the lock to `booking` only, so no change
    needed there.
  - Added the settings-form `@QuarkusTest` (`AdminSettingsTest.updateSettingsParsesAndClampsRetentionDays`):
    POSTs `bookingRetentionDays` = blank/`"0"`/`"-3"`/`"abc"`/`"99999999"`/`"30"`, asserting the
    stored value is null/null/null/null/`36500`/`30` respectively, each POST carrying every other
    field `updateSettings` reads (`ownerName`, `ownerEmail`, `timezone`, `locale`, `timeFormat`,
    `ownerNotificationsEnabled`), then confirms the settings page pre-fills `value="30"`.
  - Added window-shape tests: `aBookingStillInsideItsWindowIsKept` (boundary — a 60-day window must
    not erase a 30-day-old booking), `twoOwnersWithDifferentWindowsOnlyErasesTheOnePastItsOwnWindow`
    (a second owner is created inline with `AppUser`/`OwnerSettings`/`MeetingType`/`Booking`; only
    the owner whose own window has elapsed is erased), and, under the instance-default profile,
    `aShorterOwnerOverrideCatchesWhatTheInstanceDefaultWouldMiss` (a 10-day-old booking is erased
    under a 5-day owner override that the 14-day instance default alone would have missed — proves
    the override is actually applied, not that the default happens to agree).
  - Added `anOwnerWithNoSettingsRowStillGetsTheInstanceDefault` (instance-default profile): builds an
    owner with no `OwnerSettings` row at all and confirms the LEFT JOIN still lets the instance
    default catch its 30-day-old booking.
  - Added `aHugeOwnerRetentionValueIsClampedAndDoesNotThrow`: sets `bookingRetentionDays =
    99_999_999` directly on the row (bypassing form parsing, simulating a pre-existing or
    otherwise-written huge value), asserts `sweep()` does not throw, and that the 30-day-old booking
    stays unerased (clamped to ~100 years, still well inside the window).
  - `RetentionScheduler.sweep()`'s Javadoc now explains, explicitly: why `FOR UPDATE OF b` names
    only `booking` (a bare `FOR UPDATE` would try to lock the outer-joined `owner_settings` row
    too), and why `COALESCE(...) IS NOT NULL` is checked as its own AND-ed condition rather than
    trusted to fall out of the interval expression — Postgres's `LEAST`/`GREATEST` ignore NULL
    arguments rather than propagating them, so `LEAST(NULL, cap)` evaluates to `cap`, not null.
  - A tick failure is now caught, logged via `Log.errorf` with context (`batch=%d
    instanceDefault=%s`), and rethrown — the transaction still rolls back cleanly and the failure
    still reaches Quarkus's own scheduler failure handling, matching how `ReminderScheduler`/
    `PendingExpiryScheduler` never swallow a whole-tick failure (only a single poison item's own
    risky step, inside its own try/catch).

### Fix round 1 test evidence

```
./mvnw -o test -Dtest=RetentionSchedulerTest,RetentionSchedulerInstanceDefaultTest,AdminSettingsTest,MultiHostMessageParityTest
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- BUILD SUCCESS

./mvnw -o test -Dtest=SettingsLocaleTest,ChannelSettingsPageTest,InviteeErasureDisabledTest
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 -- BUILD SUCCESS
```

Full suite (foreground, `mvn test`):

```
[INFO] Tests run: 1208, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  03:09 min
```

(1202 -> 1208: +3 in `RetentionSchedulerTest`, +2 in `RetentionSchedulerInstanceDefaultTest`, +1 in
`AdminSettingsTest`.)

Commit: `6815c84` — `fix(privacy): clamp retention windows, fix hint copy, LEFT JOIN owner_settings`.
