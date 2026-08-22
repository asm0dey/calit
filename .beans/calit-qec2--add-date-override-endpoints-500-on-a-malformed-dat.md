---
# calit-qec2
title: createOverride 500s on a non-numeric meetingTypeId (dates already 400)
status: completed
type: bug
priority: low
created_at: 2026-08-22T10:32:04Z
updated_at: 2026-08-22T10:39:43Z
---

**Premise corrected (2026-08-22).** This bean originally claimed both `addTypeOverride`
(`AdminResource.java:1041`) and `createOverride` (`:1351`) 500 on a malformed `date`, and that
`createOverride` also 500s on a non-numeric `meetingTypeId`. Verified empirically with real
requests (see the covering tests) rather than assumed:

- **Malformed `date` on both endpoints: already 400, not 500.** `MalformedDateTimeMapper` is a
  `@Provider ExceptionMapper<DateTimeParseException>` registered *globally* (not booking-scoped
  despite living in the `booking` package), so the unguarded `LocalDate.parse(date)` on both
  endpoints already surfaces as a clean 400 with body `Malformed date/time value: <input>`, and
  persists nothing. No code change needed for this half of the original claim.
- **Non-numeric `meetingTypeId` on `createOverride`: really did 500.** No `ExceptionMapper`
  covers `NumberFormatException`, so `Long.valueOf(meetingTypeId)` threw straight through to a
  leaked 500. This was the one real defect. Fixed: `createOverride` now catches
  `NumberFormatException` around the parse and throws `BadRequestException` (same pattern already
  used at `AdminResource.java:671`'s `parseLocationType`), which JAX-RS maps to 400 with no new
  `ExceptionMapper` and no i18n key — that pattern uses a plain English message, not a
  `@Message`-bundle string, so the "needs an i18n message with de + he values" note below no
  longer applies.

Sibling of [[calit-8nlx]] — that bean's own "500 → 200" framing was corrected for the same reason
once this was verified: `createInitialDateOverride`'s pre-fix behaviour was also a 400 (same
global mapper), not a 500, and because it runs inside the SAME transaction as the meeting-type's
`t.persist()`/`persistFrames()`, the pre-fix 400 rolled back the *entire* create — no MeetingType,
no AvailabilityRule, nothing. See calit-8nlx's `## Summary of Changes` for the corrected wording.

## Notes (original — kept for history, first two now resolved as "already correct")

- ~~Low severity: both fields are `<input type="date">` / a select in the UI, so garbage only
  arrives from a crafted request~~ — still true for the one real defect (non-numeric
  `meetingTypeId`); an authenticated user can only 500 their own request, no cross-tenant
  exposure (`requireType` still runs first).
- ~~Needs an error surface these handlers do not currently have~~ — did not need one:
  `BadRequestException` is exactly that error surface and JAX-RS already renders it as 400.
- ~~Check whether a shared helper is worth it — `parseOrBadRequest` style~~ — not worth it for one
  call site; the try/catch is 4 lines inline, matching how `WriteTargetResolver.parseRef` and
  `SharedMeetingsResource`'s cohost-id parsing already do this locally rather than through a
  shared framework.

## Todo

- [x] ~~Return 400 with a user-visible message on an unparseable date in addTypeOverride and
  createOverride~~ — already true before this bean; no code change, only the false premise
  corrected.
- [x] Same for createOverride's non-numeric meetingTypeId — fixed via `BadRequestException`.
- [x] ~~i18n key with de + he values~~ — not needed; `BadRequestException` uses a plain message,
  matching the existing `parseLocationType` precedent.
- [x] Tests: malformed date and malformed meetingTypeId each return 400, persist nothing (existing
  overrides untouched by construction — the endpoint only ever inserts, never updates existing
  rows)
- [x] `mvn test` green
