---
# calit-rma2
title: Rotating the Google write target orphans existing booking events
status: completed
type: bug
priority: normal
created_at: 2026-08-16T10:07:53Z
updated_at: 2026-08-17T08:29:00Z
blocking:
    - calit-bh5t
---

A booking row stores only \`googleEventId\` — never the calendar the event was created on. Every later write (\`updateEvent\`, \`updateEventDetails\`, \`deleteEvent\`) resolves the calendar from \`GoogleCalendar.writeTarget(ownerId)\`, i.e. whatever the owner's write target is *now*.

If the owner switches their write target (or reconnects a different Google account) between booking and cancel/reschedule, calit addresses the event on the wrong calendar. Google answers 404, and since 1.20.1 \`deleteEvent\` treats 404 as "already gone" — so the cancel succeeds locally while the event stays on the old calendar forever, invisible to calit. Reschedule 404s loudly instead, which is at least noisy but still cannot reach the event.

Raised by the final review of calit-qjqb. The 410/404 tolerance is still right (the common case genuinely is a hand-deleted event); the missing piece is that calit cannot tell the two 404s apart. Since 1.20.1 the tolerated-delete INFO line logs event id + calendar id + ownerId, which is the evidence needed to find out whether this happens in practice.

## Todo

- [x] Check the logs / ask whether write-target rotation actually happens in the wild before paying for a schema change — moot: calit-bh5t (per-meeting-type write target) forces the schema change regardless, so the wait-and-see gate is dropped and this bean is promoted out of draft
- [x] Add a \`google_calendar_id\` column next to \`google_event_id\` on booking (new Flyway V*.sql — never edit an applied migration); NOT backfilled per the design decision below (NULL = unknown, resolve as today) — DONE 2026-08-17 as Task 1 of the rma2 split-plan. V26 adds \`google_calendar_id\`/\`google_credential_id\`, plus \`Booking.calendarRef()\` and \`google.CalendarRef\`. Commit b6bed22.
- [x] Address event writes by the stored calendar id, falling back to the write target for pre-migration rows — DONE 2026-08-17, Tasks 2-4 of the rma2 split-plan (commits 73925b1, e703b37, 02c6605, 1e8c3ef)
- [x] Decide what a 404 means once the calendar id is known — SPLIT OUT to calit-8dqz. This plan deliberately scoped it to logging the distinction (stored vs default-write-target) and kept 1.20.1 blanket tolerance; the tolerate-vs-surface decision needs field evidence first.

## Scope settled 2026-08-16

Promoted from draft because calit-bh5t (per-meeting-type write target) is blocked on this: once a meeting type can override the write calendar, setting an override on a type that already has future bookings orphans every one of those events on the old calendar. That is the feature working as designed, not a rare rotation — so the per-booking address has to land first.

Shape agreed:

- New `V*.sql` adds two NULLABLE columns to `booking`: `google_calendar_id VARCHAR(255)` (Googles own calendar id string) and `google_credential_id BIGINT REFERENCES google_credential(id) ON DELETE SET NULL`.
- Store Googles calendar id, NOT the local `google_calendar.id`: `CalendarSelectionService.save()` deletes and re-inserts every row for the owner on each save (`CalendarSelectionService.java:41`), so local ids churn on any settings save. The Google-side id survives untick/re-tick.
- Credential id is needed because the calendar id alone carries no token — `writeContext()` (`GoogleCalendarPort.java:311-318`) needs a `GoogleCredential`. Recovering it via `GoogleCalendar.findByGoogleId` is unreliable: the row may be gone (calendar unticked) and the `(credential, calendar)` uniqueness means a shared calendar can match two rows. Credential rows survive reconnect of the same account (`GoogleTokenService:152` upserts by owner+sub); only an explicit disconnect nulls the column, which degrades to todays fallback.
- DO NOT backfill existing rows. Stamping them with the current write target is a guess that is wrong exactly for the affected bookings, converting honest "unknown" into confident-wrong. NULL = pre-migration, resolve as today.
- 404 handling becomes three-state: calendar known -> 404 means genuinely hand-deleted (keep the 1.20.1 tolerance); calendar NULL -> unknown, keep todays lenient behaviour.

## Amended 2026-08-17 (from calit-bh5t design)

`booking.google_calendar_id` is **text**, not VARCHAR(255) — same reasoning as the per-type override columns in `docs/superpowers/specs/2026-08-17-per-meeting-type-write-target-design.md`. Entity field needs `@Column(columnDefinition = "text")` (Hibernate runs validate-only).

Port shape decided 2026-08-17: new `record CalendarRef(Long credentialId, String googleCalendarId)` in `google/`; a null ref means "resolve as today" (pre-migration rows). `updateEvent`, `updateEventDetails`, `deleteEvent` take `(ownerId, ref, eventId, ...)`; `CreatedEvent` reports the ref it wrote to so `BookingService` can persist it. calit-bh5t reuses the same type for the create side.

## Summary of Changes

Shipped in PR #133 (branch `feat/per-booking-calendar-address`), 6 code commits over 5 planned tasks.

**Schema.** `V26__booking_calendar_address.sql` adds two nullable columns to `booking`:
`google_calendar_id text` (Google’s own calendar id string, not the local `google_calendar.id` —
`CalendarSelectionService.save()` deletes and re-inserts every row per owner on each save, so
local ids churn) and `google_credential_id bigint REFERENCES google_credential(id) ON DELETE SET NULL`.

**Types.** New `google/CalendarRef(Long credentialId, String googleCalendarId)`.
`Booking.calendarRef()` returns one, or null. `CreatedEvent` gained a 4th component reporting the
address the event was actually written to.

**Behaviour.** `CalendarPort.updateEvent` / `updateEventDetails` / `deleteEvent` each take a
`CalendarRef`. `GoogleCalendarPort.writeAddress(ownerId, ref)` resolves it behind an owner-scope
guard — a ref that is null, or whose credential row is missing or belongs to another owner, falls
back to the owner’s current write target rather than throwing. All 7 later-write call sites in
`BookingService` now pass `booking.calendarRef()`. Reschedule captures `priorRef` *before* the
re-approval block clears the address, so the delete still reaches the old calendar. Both address
columns are cleared at exactly the two sites that clear `googleEventId`, and nowhere else.
`groupEventId` + `organizerOwnerOf` collapsed into one `groupEventRow(UUID)` lookup so id, owner,
and ref always come from the same row.

**No backfill — deliberate.** NULL means “address unknown, resolve as before”. Stamping existing
rows with the current write target would be confidently wrong for exactly the bookings this bug
affects. The rationale is recorded in the migration body, not just the plan.

**Create side unchanged.** `createEvent` still resolves the owner’s default write target; the
per-meeting-type override is calit-bh5t’s job and reuses `CalendarRef`.

**Verification.** Full suite 827/827 green (run before and after the two folded Renovate bumps),
`spotless:check` clean, `quarkus:dev` boot applied Flyway through v26 with Hibernate `validate`
raising no mismatch. Every task passed a spec+quality review; a final whole-branch review confirmed
the owner-scope guard, migration safety (metadata-only ADD COLUMN; rollback to a pre-V26 app is
safe under Flyway’s default `*:future` ignore) and the NULL-address paths.

**Split out, not dropped:** calit-8dqz (what a 404 should mean now the calendar is known),
calit-64hy (disconnect leaves a half-address that 500s on reschedule), calit-wsdr (index the FK
column), calit-vi8n (cosmetic tidy-ups).

**Release note owed:** the behaviour change is user-observable — cancelling now removes the event
from the calendar it was created on even after a write-target rotation or a new account — so it
needs a changelog entry on `docs-site` in whichever release ships it.
