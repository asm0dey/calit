---
# calit-bh5t
title: Per-meeting-type write target
status: todo
type: feature
priority: normal
created_at: 2026-08-16T11:10:11Z
updated_at: 2026-08-16T11:18:48Z
blocked_by:
    - calit-rma2
---

Today each owner has exactly one write calendar: one `GoogleCalendar` row with `writeTarget = true` (uniqueness enforced in `CalendarSelectionService.java:38`), resolved live on every write via `GoogleCalendar.writeTarget(ownerId)` (`GoogleCalendarPort.java:305`). So every meeting type of an owner lands on the same calendar.

Ask: let a meeting type pick its own write calendar — e.g. "Coaching" -> personal calendar, "Client intro" -> work calendar — falling back to the owner-level write target when unset.

Sketch:

- New nullable column on `meeting_type` pointing at the chosen `google_calendar` row (new Flyway `V*.sql`; never edit an applied migration). Null = use the owner write target, so existing types keep todays behaviour.
- `GoogleCalendarPort` write paths take the meeting type into account instead of calling `GoogleCalendar.writeTarget(ownerId)` blind.
- Owner-scoped: the chosen calendar row must belong to `currentOwner.id()` — validate on save, not just in the UI.
- UI on the meeting-type edit form (`AdminResource`), only shown when Google is connected; degraded/no-Google mode must stay working.
- Google Meet interaction: `GoogleCalendar.writeTargetBlocksMeet(ownerId)` (`AdminResource.java:604,619`) currently gates `GOOGLE_MEET` location on the owner-level target — that check has to follow the per-type calendar.
- New/changed user-facing strings need `de` + `he` translations in the same change.

Interacts with calit-rma2: once the write calendar can differ per meeting type, a booking really must record which calendar its event lives on, otherwise cancel/reschedule addressing gets worse than the rotation case rma2 describes.

## Todo

- [x] Decide UX: per-type setting is an OPTIONAL override; unset = owner default (decided 2026-08-16)
- [ ] Rename the owner-level setting to "default write target" in UI strings + de/he translations
- [ ] Rename in code where it stays readable (GoogleCalendar.writeTarget -> defaultWriteTarget); keep the google_calendar.write_target COLUMN as is (rename would cost a migration for nothing)
- [ ] Migration for the per-type calendar column (nullable, null = owner write target)
- [ ] Resolve the write calendar per meeting type in the Google write paths
- [ ] Owner-scope validation of the selected calendar row
- [ ] Follow the per-type calendar in the Meet gating check
- [ ] Tests, incl. degraded no-Google mode
- [ ] de + he translations for new strings
- [ ] Docs on `docs-site` branch

## Decisions

- The per-meeting-type calendar is an **optional override**, nullable. Unset (the default, and what every existing meeting type gets) means "use the owner default", so behaviour is unchanged unless someone opts in.
- The owner-level `write_target` is therefore renamed in user-facing terms to **default write target** — it stops being "the calendar" and becomes the fallback. UI labels + `de`/`he` values change; the DB column stays `write_target` (a rename would be a migration and a Hibernate mapping change for zero behaviour).
- Resolution order for a write: meeting type override -> owner default write target -> the existing "no write target selected" error.
- Uniqueness rule is unchanged: still at most one default per owner (`idx_google_calendar_single_write_target`, `CalendarSelectionService.java:38`). The override does not need that constraint — it points at one calendar per meeting type by definition.
