---
# calit-zpj6
title: 'Per-meeting-type control over which invitee fields are required, optional, or hidden (GH #130)'
status: todo
type: feature
priority: normal
created_at: 2026-08-22T16:37:37Z
updated_at: 2026-08-22T16:44:51Z
---

Upstream: https://github.com/asm0dey/calit/issues/130 (reporter @h200101)

Ask (widened): the reporter wanted a per-type toggle hiding the Guests field on the booking form.
Generalised: **every invitee field except the email is configurable per meeting type** — required,
optional, or hidden — set when creating or editing the meeting type. Email is the one field that
stays mandatory and gets no toggle (it is the booking's identity + the manage-link recipient).

## Current state

Built-in booking-form fields (`templates/PublicResource/book.html:80-88`):

| Field | Today | After |
|---|---|---|
| `inviteeName` | always shown, `required` | REQUIRED / OPTIONAL / HIDDEN per type |
| `inviteeEmail` | always shown, `required` | unchanged — always required, no toggle |
| guests chips (`_guestschips.html`) | always shown | OPTIONAL / HIDDEN per type |
| custom `BookingField`s | per-field `required` flag, already honoured by `book.html` | no work — custom fields are two-state (required / optional) by design; a field nobody should fill is simply not on that type's form, so they get no HIDDEN state |

So the only new machinery is for the two built-ins.

## Design (decided)

Two enum columns on `meeting_type`, both defaulting to today's behaviour so existing types are untouched:

```
name_mode   varchar(16) not null default 'REQUIRED'   -- REQUIRED | OPTIONAL | HIDDEN
guests_mode varchar(16) not null default 'OPTIONAL'   -- OPTIONAL | HIDDEN
```

`booking.invitee_name` stays `NOT NULL`. When the name arrives blank (OPTIONAL left empty, or HIDDEN),
`BookingService` stores the **email local-part** as `inviteeName`. That keeps email subjects, .ics
summaries, calendar event titles, and the admin lists working with zero null-handling audit across
~30 `inviteeName` call sites in `EmailService`/`BookingService`. Rejected alternative: making the
column nullable.

## Touch points

- `src/main/resources/db/migration/V29__*.sql` — pick the next free V number at implementation time
  (current head is `V28__seed_default_availability.sql`); never edit an applied migration
- `domain/MeetingType.java` — `nameMode` / `guestsMode` fields (enum + `@Enumerated(EnumType.STRING)`,
  matching the existing `LocationType` pattern)
- `templates/AdminResource/meetingTypes.html` (create form) **and**
  `templates/AdminResource/meetingTypeDetail.html` (edit form) — the selectors
- `web/AdminResource.java` — bind + persist both on create and update
- `templates/PublicResource/book.html:80-88` — name input: drop `required` when OPTIONAL, skip the
  input entirely when HIDDEN; skip the `{#include PublicResource/_guestschips ...}` when guests HIDDEN
- `templates/PublicResource/manage.html` — the booker's reschedule/edit page also edits guests; honour
  `guestsMode` there too
- `booking/BookingService.java` — server-side enforcement, not just hidden inputs:
  - blank/absent name + `nameMode != REQUIRED` → derive from email local-part instead of throwing
    (`validateInputBounds`, line ~573, currently rejects a blank name unconditionally)
  - `guestsMode == HIDDEN` → drop any submitted guest emails (`persistGuests`, line ~475) and on
    reschedule (`reschedule(...)`, line ~752 — guest lists are editable there)
  - `nameMode == REQUIRED` keeps today's rejection
- `booking/BookingResource.java` — the JSON API takes the same fields; it must go through the same
  policy, not just the HTML form path
- i18n: new labels in `AdminMessages` (+`de`/`he` values in `messages/adm_{de,he}.properties`).
  No English-fallback-only keys.

## Todos

- [ ] Migration adding `meeting_type.name_mode` + `meeting_type.guests_mode` with today's defaults
- [ ] `MeetingType.nameMode` / `MeetingType.guestsMode` (+ enum type)
- [ ] Admin create form: both selectors, persisted
- [ ] Admin edit form: both selectors, persisted
- [ ] `book.html`: name `required`/hidden per `nameMode`; guests include skipped per `guestsMode`
- [ ] `manage.html`: guest editing skipped when guests HIDDEN
- [ ] `BookingService`: blank name → email local-part when not REQUIRED; still reject blank when REQUIRED
- [ ] `BookingService`: drop submitted guests for a HIDDEN type, on booking **and** on reschedule
- [ ] `BookingResource` (JSON API) enforces the same policy
- [ ] i18n: new admin labels with de + he translations
- [ ] Tests: no name input when HIDDEN; no guests input when HIDDEN; posting a name/guests anyway for a
      HIDDEN type still books and stores none of it; blank name on an OPTIONAL type books with the
      email local-part; blank name on a REQUIRED type still 400s
- [ ] Docs: `docs-site` usage page + `## Unreleased` changelog bullet at merge
