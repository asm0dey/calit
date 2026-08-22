---
# calit-nyg8
title: 'Per-meeting-type note shown on the booking page (GH #128)'
status: todo
type: feature
created_at: 2026-08-22T16:37:23Z
updated_at: 2026-08-22T16:37:23Z
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

- [ ] Decide reuse-`description` vs new `booking_note` column (ask on the issue)
- [ ] If new column: `V29__meeting_type_booking_note.sql` (never edit an applied migration)
- [ ] Render the note in the booking form column of `PublicResource/book.html`
- [ ] Admin edit UI for it (`AdminResource` meeting-type form) if a new field
- [ ] i18n: `@Message` default + de/he values for any new label
- [ ] Test: public booking page shows the note for a type that has one, absent when blank
- [ ] Docs: `docs-site` branch (usage page) + `## Unreleased` changelog bullet at merge
