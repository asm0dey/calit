---
# calit-8nlx
title: Date-override create path 500s on an unparseable overrideDate
status: completed
type: bug
priority: normal
created_at: 2026-08-22T10:21:53Z
updated_at: 2026-08-22T10:29:30Z
---

`AdminResource.createInitialDateOverride` calls `LocalDate.parse(date)` with no guard, so a crafted `overrideDate=x` on `POST /me/meeting-types` throws `DateTimeParseException` out of the transaction and returns 500 instead of dropping the value.

Pre-existing — not introduced by calit-9d76 — but that change made the asymmetry conspicuous: the working-hours half of the same form now routes through `persistFrames`, which catches `DateTimeParseException | IllegalArgumentException`, skips blanks and drops inverted frames, while the date-override half directly below it still 500s on garbage.

## Notes

- Same guard shape as `persistFrames` (`AdminResource.java:1143`): try/catch around the parse, skip the value rather than fail the request.
- Check `persistWindows` and the window start/end parsing on the same path for the same hole.
- The bulk-save date-override endpoints may already guard — verify before assuming this is only the create path.

## Todo

- [x] Guard the overrideDate parse (and the window times, if unguarded) on the create path
- [x] Test: a garbage overrideDate on create returns 200 and persists no override, not 500
- [x] `mvn test` green


## Summary of Changes

- `createInitialDateOverride` (`AdminResource.java`): guards `LocalDate.parse(date)` in a
  try/catch, skipping the override entirely on an unparseable date (matching `persistFrames`'s
  catch-and-skip shape) instead of throwing out of the transaction.
- `persistWindows` (shared helper, called by the create path AND by `addTypeOverride` /
  `createOverride`): guards `LocalTime.parse` for both `windowStart`/`windowEnd`, skipping just
  that malformed window row rather than 500ing the whole save.
- Investigation: no dedicated "bulk-save" date-override endpoint exists (unlike availability,
  which has `/availability/bulk` and `/meeting-types/{id}/availability/bulk`). Date overrides are
  written by three single-add endpoints instead: this create path (now fixed), the per-type
  `POST /meeting-types/{id}/date-overrides` (`addTypeOverride`), and the owner-level
  `POST /date-overrides` (`createOverride`). The latter two still call `LocalDate.parse(date)`
  unguarded and share the identical crash risk, but fixing/testing them touches two more resource
  methods, two more templates' expected behaviour, and two other test files
  (`AdminMeetingTypeDetailTest`, `AdminDateOverridesTest`) not named in this bean's scope — left
  alone here and flagged as a follow-up candidate. They DO already benefit from the `persistWindows`
  fix above, since they call the same shared method. Separately, `SharedMeetingsResource.addOverride`
  (a fourth, independent date-override-add path for cohosted meetings) already guards both date and
  window parsing via its own `parseDateOrNull`/`parseTimeOrNull` helpers — confirming this is the
  established safe pattern elsewhere in the codebase that `AdminResource`'s two remaining unguarded
  siblings should eventually be brought in line with.
- Tests added to `AdminMeetingTypeFormTest`, next to `createPersistsPerTypeDateOverrideWithWindow`:
  `createWithGarbageOverrideDateStillCreatesTheType` (garbage date -> 200, no `DateOverride`
  persisted, `MeetingType` still created) and `createWithGarbageWindowTimeSkipsThatWindowButKeepsTheOverride`
  (valid date + one garbage window + one valid window -> 200, override persisted, only the valid
  window persisted).
