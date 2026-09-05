---
# calit-nyg8
title: 'Per-meeting-type note shown on the booking page (GH #128)'
status: completed
type: feature
priority: normal
created_at: 2026-08-22T16:37:23Z
updated_at: 2026-09-05T10:01:14Z
---

Upstream: https://github.com/asm0dey/calit/issues/128 (reporter @h200101)

Ask: owner can attach free text to a meeting type that is displayed to whoever books it,
e.g. "Please select the appropriate date and time for our therapy session. Don't worry if
you are not on time; things happen."

## Current state

`MeetingType.description` (text) already exists and already renders:
- `templates/PublicResource/book.html:40` — above the slot picker
- `templates/PublicResource/landing.html:21` — in the public type list

So the capability half-exists. Open question below decides the shape of the work.

## Open question (decide first)

Is `description` enough, with better placement/prominence on the booking step, or does the
owner want a *second*, booking-form-scoped note (description = "what this meeting is",
note = "instructions while booking")? The reporter's screenshot points at the booking form
area, below the fields — where `description` is not shown today.

Lazy path: reuse `description`, also render it in the form column of `book.html`. No
migration, no new admin field, no new i18n keys.
Full path: new `booking_note` text column + admin field + render.

Ask the reporter / owner before writing a migration.

## Todos

- [x] Decide reuse-`description` vs new `booking_note` column — reuse: the column existed already and was simply unreachable from any form
- [x] No migration needed — `description TEXT` is already in `V1__core_schema.sql:15`
- [x] Render — left rail (`book.html:40`) + landing card (`landing.html:21`) already did this; kept as-is (owner picked left rail over the form column)
- [x] Admin edit UI — textarea on both the create form (`meetingTypes.html`) and the detail basics form (`meetingTypeDetail.html`)
- [x] i18n: `adm_meetingTypes_label_description` + de/he values
- [x] Test: `MeetingTypeDescriptionTest` — 8 cases, admin form → column → public booking page + landing card
- [x] Docs: `docs-site` usage page + `## Unreleased` changelog bullet (PR #173)

## Summary of Changes

`MeetingType.description` turned out to be a write-nothing field: in the schema since
`V1__core_schema.sql:15`, rendered on the booking page (`book.html:40`) and the landing card
(`landing.html:21`), but never written by any form. So #128 was "expose the field", not "add one".

- `AdminResource.applyEditableFields` now takes and writes `description` (blank → `null`); create
  and edit share that method, so one line covers both.
- `@RestForm String description` on `createMeetingType` + `editMeetingType`.
- Textarea (`maxlength=2000`) on the create form and the detail page's Basics section.
- i18n key `adm_meetingTypes_label_description` with de + he.
- No migration.

Placement: kept in the left rail rather than the booking-form column the reporter's screenshot
pointed at — under the name and duration, above the fold, first on mobile.

Known side effect, documented rather than changed: `Booking.effectiveDescription(type)` falls back
to the type description (`BookingService.java:608`), so the note also becomes the Google Calendar
event body and the `.ics` DESCRIPTION.

Tests: `MeetingTypeDescriptionTest`, 8 cases. Full suite 1044 green.

PRs: [#172](https://github.com/asm0dey/calit/pull/172) (code), [#173](https://github.com/asm0dey/calit/pull/173) (docs-site).
