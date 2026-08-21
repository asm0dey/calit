---
# calit-v05s
title: 'Fix wave: per-meeting-type write override review findings'
status: completed
type: task
priority: normal
created_at: 2026-08-21T18:30:05Z
updated_at: 2026-08-21T18:41:34Z
---

Apply the fix wave from the final whole-branch review: Fix1(Important) SharedMeetingsResource write routing, Fix2 KEEP sentinel template hardcode, Fix3 vocab sweep camelCase, Fix4 dead overload, Fix5 owner predicate, Fix6 selected-option test. See dispatch instructions for full detail.


## Summary of Changes

All 7 fixes applied (Fix 7 added mid-wave by coordinator after a JaCoCo coverage pass).

- Fix 1 (Important): `SharedMeetingsResource.saveBuffers` now routes the write-calendar save to
  `MeetingType`'s own columns when `currentOwner` is the Creator, matching how `writeOverride()`
  already routes the read -- previously a Creator's save on the shared page silently landed on a
  `MeetingTypeHost` row nothing reads. Fixed the misleading comment. New test:
  `SharedWriteCalendarTest#creatorHittingTheSharedUrlDirectlyWritesTheOverrideOntoTheMeetingTypeNotTheHostRow`.
- Fix 2: Added/retargeted tests asserting the rendered `<option value="...">` equals
  `WriteTargetResolver.KEEP`, on both the Admin and Shared pages.
- Fix 3: Finished the write-target vocabulary sweep -- renamed every remaining camelCase/hyphenated
  "default write target" survivor across `src/main`/`src/test` (including `CalendarRef.java`, now
  in scope) to "write target". Search confirms exactly one survivor left: the untouched `V26`
  migration.
- Fix 4: Removed the dead `AdminResource.allowedLocationTypes(MeetingType)` overload; folded its
  body into the zero-arg caller.
- Fix 5: Added an `@implNote` to `AdminResource.bookingsStayingBehind` documenting why it is
  deliberately NOT scoped by an owner predicate (a shared type's "bookings that stay behind" is a
  property of the type, not of one Host -- see the code comment in `SharedMeetingsResource
  .saveBuffers`) and naming the caller's authorization obligation. Chose javadoc over a predicate
  because a predicate would silently change (regress) already-intentional behaviour.
- Fix 6: Added a "live override renders as the selected option" regression-pin test per page
  (Admin + Shared).
- Fix 7: Added `SharedWriteCalendarTest#movingTheCohostsCalendarSaysUpcomingBookingsStayBehind`
  covering the previously-uncovered co-host moved-bookings notice branch.

No new `@Message` keys were introduced (Fix 1's chosen option avoided one), so no i18n follow-up.
Full report: `.superpowers/sdd/2026-08-17-per-meeting-type-write-target/final-fix-report.md`.

Tests run (targeted, per dispatch instructions -- full suite not run):
- `AdminWriteCalendarTest`, `SharedWriteCalendarTest`, `WriteTargetResolverTest`,
  `CreateEventTargetTest`, `BookingWriteTargetOverrideTest`: 36/36 green.
- `AdminMeetGatingTest`, `AdminMeetGatingOverrideTest`, `AdminMeetingTypeDetailTest`,
  `AdminMeetingTypeFormTest`, `AdminMeetingTypesTest`, `StoredCalendarAddressTest`: 43/43 green.
- `DeleteEventAlreadyGoneTest`: 4/4 green.
- `spotless:apply` / `spotless:check`: clean.
