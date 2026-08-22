---
# calit-d0qp
title: Whole-branch review fix wave for fix/bug-sweep
status: completed
type: task
priority: normal
created_at: 2026-08-22T14:08:50Z
updated_at: 2026-08-22T14:21:52Z
---

Apply the 10-item fix wave from the whole-branch code review of fix/bug-sweep, before the branch becomes a PR.

Two merge blockers, both 'fix landed at the call site, not the invariant':
- [x] Blocker 1 (calit-h8mb second path): POST /api/bookings resolves the username itself and skips the !owner.enabled guard added to PublicResource.resolveOwner. Public unauthenticated endpoint; MeetingHosts.bookable returns true for single-host types so nothing downstream saves it.
- [x] Blocker 2 (calit-4whp second path): MeSetupResource:103 writes s.timezone unguarded. Move the invariant onto OwnerSettings (coerceZone + zoneIds) and route both writers through it; delete the two duplicate private zoneIds() helpers.

Minors:
- [x] 3. Task4 x Task5 interaction test in AdminMeetGatingOverrideTest (locationType+writeCalendar in one POST)
- [x] 4. Coverage for the two reworded i18n keys (revokeConfirm_count, removeConfirm_count)
- [x] 5. Collapse duplicated fallback expression in Layout.java:74 (keep inverted-ternary protection)
- [x] 6. Reword stale Layout.tzBar javadoc
- [x] 7. Point thePickerDefaultsToTheZoneThePageWasAuthoredIn at a page with a picker
- [x] 8. German adm_times_shown_in -> 'Alle Zeiten in {zone}'
- [x] 9. Fix PublicDisabledOwnerTest POST field names (inviteeName/inviteeEmail)
- [x] 10. Reopen/correct calit-h8mb and calit-4whp bean bodies

Out of scope by explicit decision: token-keyed reschedule path (PublicResource:474), Hebrew drift at adm_he:143/146, extracting the before/after comparison blocks.


## Summary of Changes

All ten review items applied; full suite **921 tests, 0 failures, 0 errors, BUILD SUCCESS**
(916 before + 5 new).

### Blockers (one commit each, both TDD)

1. **`POST /api/bookings` booked a disabled owner.** `BookingResource.create` resolves the
   username itself and never routes through the guarded `PublicResource.resolveOwner`. RED:
   `PublicDisabledOwnerTest.apiBookingPostIs404` returned **201 Created** (the slot was moved
   inside the type's 60-day horizon so the red demonstrates a real booking, not an incidental
   409). Fixed with the same `owner == null || !owner.enabled` guard at that call site.
2. **`MeSetupResource:103` wrote `timezone` unguarded.** RED:
   `MeSetupResourceTest.wizardCoercesAnUnknownTimezoneToUtc` stored `Not/AZone`. Moved the
   invariant onto the entity as `OwnerSettings.zoneIds()` + `OwnerSettings.coerceZone(String)`,
   routed both writers through it, deleted both duplicate `private static zoneIds()` helpers and
   repointed all four picker call sites. Added `coerceZone` unit tests (known zone, unknown, null,
   blank, and every id the picker can offer).

### Minors (one shared commit)

3. `AdminMeetGatingOverrideTest.aMeetRejectionRollsBackTheWriteCalendarMoveSubmittedInTheSameSave`
   posts `locationType` and `writeCalendar` together for the first time, pinning that the calendar
   move rolls back with the refused save. Mutation-verified non-vacuous.
4. Full-wording assertions for the two reworded count keys, in
   `SharedMeetingsResourceTest.revokeWithFutureBookingRendersInterstitialAndDoesNotRemoveHost` and
   `HostRemovalInterstitialTest.removeWithFutureBookingRendersConfirmAndDoesNotRemoveHost`. Both
   previously asserted only `containsString("1")`, which survived the rewording.
5. `Layout.java` `render()` now reads the shared `initial` instead of repeating the fallback.
   The inverted-ternary protection moved to `thePickerDefaultsToTheZoneThePageWasAuthoredIn` and
   was **mutation-verified**: inverting `initial` to `detected || document.body.dataset.tz` fails
   that test.
6. `Layout.tzBar` javadoc rewritten to describe the actual `body[data-tz]`-first behaviour.
7. `thePickerDefaultsToTheZoneThePageWasAuthoredIn` now runs against
   `/me/bookings/{id}/manage`, which really renders `#tz-picker`, and asserts that it does.
8. German `adm_times_shown_in` → `Alle Zeiten in {zone}`.
9. `PublicDisabledOwnerTest`'s form POST uses the real `inviteeName`/`inviteeEmail` contract.
10. `calit-h8mb` and `calit-4whp` each carry a "Second path found by the whole-branch review"
    section; both stay `completed`.

Out of scope by explicit decision and untouched: the token-keyed reschedule path
(`PublicResource:474`), the pre-existing Hebrew drift at `adm_he.properties:143/146`, and
extracting the two `before`/`after` comparison blocks.
