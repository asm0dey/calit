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

**Correction (2026-08-22, from calit-qec2's investigation):** the "500" claim above was never
verified and turned out to be wrong. `MalformedDateTimeMapper` is a globally-registered
`@Provider ExceptionMapper<DateTimeParseException>`, so the pre-fix status was actually **400**,
not 500. The real defect was worse than a wrong status code, though: `createInitialDateOverride`
ran inside the SAME `QuarkusTransaction.requiringNew()` as `t.persist()` and `persistFrames()`, so
that 400 rolled back the WHOLE meeting-type create — no `MeetingType`, no `AvailabilityRule`,
nothing, on a garbage date-override value that has nothing to do with the rest of the form. The fix
below still stands and is still valuable; it's the framing that needed correcting. See the amended
`## Summary of Changes` below.

## Notes

- Same guard shape as `persistFrames` (`AdminResource.java:1143`): try/catch around the parse, skip the value rather than fail the request.
- Check `persistWindows` and the window start/end parsing on the same path for the same hole.
- The bulk-save date-override endpoints may already guard — verify before assuming this is only the create path.

## Todo

- [x] Guard the overrideDate parse (and the window times, if unguarded) on the create path
- [x] Test: a garbage overrideDate on create returns 200 and persists no override, not 500
- [x] `mvn test` green


## Summary of Changes

**Corrected value statement (2026-08-22):** this fix's value was never "500 → 200". Empirically,
the pre-fix status was already 400 (global `MalformedDateTimeMapper`), and because the parse ran
inside the meeting-type create's own transaction, that 400 rejected the WHOLE create — no
`MeetingType`, no `AvailabilityRule` from the same submission, nothing persisted at all, on a
garbage value in an optional secondary field. The actual, correct value: **"the whole create form
was rejected and no meeting type was created" → "the meeting type and its working hours still
save; only the invalid optional date-override value is skipped."**

- `createInitialDateOverride` (`AdminResource.java`): guards `LocalDate.parse(date)` in a
  try/catch, skipping the override entirely on an unparseable date (matching `persistFrames`'s
  catch-and-skip shape) instead of throwing out of the shared create transaction and rolling back
  the meeting type itself along with it.
- `persistWindows` (shared helper, called by the create path AND by `addTypeOverride` /
  `createOverride`): guards `LocalTime.parse` for both `windowStart`/`windowEnd`, skipping just
  that malformed window row instead of throwing (same transaction-rollback risk as the date, for
  callers where `persistWindows` isn't the last statement in an otherwise-unrelated transaction).
- Investigation: no dedicated "bulk-save" date-override endpoint exists (unlike availability,
  which has `/availability/bulk` and `/meeting-types/{id}/availability/bulk`). Date overrides are
  written by three single-add endpoints instead: this create path (now fixed), the per-type
  `POST /meeting-types/{id}/date-overrides` (`addTypeOverride`), and the owner-level
  `POST /date-overrides` (`createOverride`).
- **Correction (2026-08-22, calit-qec2):** the original claim here that `addTypeOverride` and
  `createOverride` "share the identical crash risk" (500) was wrong, and was never verified
  empirically before being written. Both already return 400 via the same global
  `MalformedDateTimeMapper` on a malformed date, and — unlike this create path — neither call sits
  inside a transaction shared with unrelated persistence, so their 400 doesn't take anything else
  down with it. The one real defect on that pair was `createOverride`'s unguarded
  `Long.valueOf(meetingTypeId)` (`NumberFormatException`, no mapper, genuine 500) — fixed
  separately in calit-qec2 via `BadRequestException`, matching the codebase's existing
  `parseLocationType` precedent. Separately, `SharedMeetingsResource.addOverride` (a fourth,
  independent date-override-add path for cohosted meetings) already guards both date and window
  parsing via its own `parseDateOrNull`/`parseTimeOrNull` helpers.
- Tests added to `AdminMeetingTypeFormTest`, next to `createPersistsPerTypeDateOverrideWithWindow`:
  `createWithGarbageOverrideDateStillCreatesTheType` (garbage date -> 200, no `DateOverride`
  persisted, `MeetingType` still created) and `createWithGarbageWindowTimeSkipsThatWindowButKeepsTheOverride`
  (valid date + one garbage window + one valid window -> 200, override persisted, only the valid
  window persisted).
