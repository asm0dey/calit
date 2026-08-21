---
# calit-bh5t
title: Per-meeting-type write target
status: completed
type: feature
priority: normal
created_at: 2026-08-16T11:10:11Z
updated_at: 2026-08-21T18:51:00Z
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
- [x] ~~Rename the owner-level setting to "default write target" in UI strings + de/he translations~~ — DROPPED in design: `CONTEXT.md` puts "default write target" on the glossary avoid list (the write target already *is* the default) and reserves that phrasing under *Fallback address*.
- [x] ~~Rename in code (GoogleCalendar.writeTarget -> defaultWriteTarget)~~ — DROPPED with the above. `GoogleCalendar.writeTarget` keeps its name; the column was never going to change.
- [x] Migration for the write-override columns — `V27__meeting_type_write_target.sql`, additive and nullable on both `meeting_type` and `meeting_type_host`, no backfill
- [x] Resolve the write calendar per (type, Host) — `WriteTargetResolver`, consumed by `BookingService` and `CalendarPort.createEvent`
- [x] Owner-scope validation server-side — `requireOwnedCalendar` on both pages, re-checked again in `GoogleCalendarPort.writeContext`
- [x] Meet gate follows the resolved calendar — `WriteTargetResolver.blocksMeet`, answered against the Creator's calendar by design
- [x] Tests incl. degraded no-Google mode — 98.2% new-code line coverage; cross-tenant and never-erase properties pinned with deliberate-break-verified tests
- [x] de + he translations — six new keys, bundle parity verified 0 missing / 0 extra in both locales
- [x] Docs on `docs-site` — branch `docs/per-meeting-type-write-override`: changelog bullet under `## Unreleased` plus a `google-oauth.md` section (PR number still `#TBD`)

## Decisions

- The per-meeting-type calendar is an **optional override**, nullable. Unset (the default, and what every existing meeting type gets) means "use the owner default", so behaviour is unchanged unless someone opts in.
- The owner-level `write_target` is therefore renamed in user-facing terms to **default write target** — it stops being "the calendar" and becomes the fallback. UI labels + `de`/`he` values change; the DB column stays `write_target` (a rename would be a migration and a Hibernate mapping change for zero behaviour).
- Resolution order for a write: meeting type override -> owner default write target -> the existing "no write target selected" error.
- Uniqueness rule is unchanged: still at most one default per owner (`idx_google_calendar_single_write_target`, `CalendarSelectionService.java:38`). The override does not need that constraint — it points at one calendar per meeting type by definition.

## Design settled 2026-08-17

Spec: `docs/superpowers/specs/2026-08-17-per-meeting-type-write-target-design.md` (commit 065c4f0).

- Override is per **(type, host)**: `meeting_type` columns hold the creator's choice, `meeting_type_host` columns hold each co-host's own choice for that shared type. Resolution runs for whichever owner `MeetingHosts.chooseOrganizer` picks; that method is unchanged.
- Stored as a (google_credential_id BIGINT REFERENCES google_credential ON DELETE SET NULL, google_calendar_id **text**) pair, not an FK to `google_calendar.id` — `CalendarSelectionService.save()` deletes+reinserts every row per save, so local ids churn. `text` not VARCHAR(255): no reason for the cap; entity needs `columnDefinition = "text"` under validate-only Hibernate.
- Dangling override (calendar unticked / account disconnected) -> fall back to the owner default, WARN log (type id, calendar id, ownerId), and the edit form shows "calendar no longer available — using default". Never fails the booking.
- New `WriteTargetResolver` in `google/` owns the resolution order; `createEvent` takes the resolved target instead of calling `requireWriteTarget(ownerId)`.
- Meet gate (AdminResource:604,619) follows the **creator's** resolved calendar only. Co-host overrides do not affect it; a co-host organizer with a non-Meet calendar degrades via the existing `handleCreateFailure`.
- Co-host UI goes on the existing `/me/shared/{typeId}/availability` page (`SharedMeetingsResource`, next to the per-host buffers form) — no new page.
- calit-rma2 ships and is verified FIRST, as its own change.

## Grilled 2026-08-17 (design interview)

Plan: `docs/superpowers/plans/2026-08-17-per-meeting-type-write-target.md`.

- **Vocabulary** (`CONTEXT.md`): the Owner-level calendar stays the **write target** (its definition already means "by default"); the new per-(type, host) choice is a **write override**; an override naming a calendar the Host no longer has is a **dangling override**. The earlier plan to rename `GoogleCalendar.writeTarget` -> `defaultWriteTarget` and relabel the Google page is DROPPED — the glossary reserves that phrasing under *Fallback address*.
- **Disconnect half-row** (`google_credential_id` nulled by the FK, `google_calendar_id` left behind) reads as a DANGLING override, not as "unset": WARN on write, warning alert on the form. The resolver keys "is there an override?" off the calendar id alone.
- **Never silently erased**: the dangling entry in the picker carries the sentinel value `keep`, so saving anything else on that form round-trips the stored override untouched. Only an explicit pick clears or changes it.
- **Counted notice on a move**: after a save that changes the write override, the page says how many upcoming bookings stay on the calendar they were created on (`AdminResource.bookingsStayingBehind`). Clearing an override compares against the write target, not null.
- **Create form gets the picker too**, not just the detail page; no `keep`/dangling state there, and the Meet gate sees the chosen calendar.
- **Co-host + Meet unchanged**: the gate runs at edit time on the Creator's calendar, and the organizer is only known at booking time, so a Co-host organizer on a non-Meet calendar still degrades via `handleCreateFailure`.
- ADR written: `docs/adr/0004-the-write-override-names-a-calendar-by-its-google-identity.md`.

## Summary of Changes

Shipped on `feat/per-meeting-type-write-override` (17+ commits off `71750d3`), nine planned tasks executed with a fresh implementer and an independent review per task, plus a whole-branch review and one fix wave.

**What a Host gets:** any meeting type can be given its own connected Google calendar — a **write override** — and that type's events are created there instead of on their write target. Each Host of a shared type sets their own, independently. Unset is the normal case and means "use my write target", so nothing changes for anyone who ignores the feature, and no migration or configuration step is required.

**Shape:** an override stores `(google_credential_id, google_calendar_id)` rather than an FK to the local calendar row — see `docs/adr/0004`. Calendar selection saves by delete-and-reinsert, so an FK would be orphaned by an unrelated settings save; a Google identity survives an untick and a later re-tick.

**The guarantee that drove the design:** a **dangling override** — calendar unticked, or its account disconnected so the FK nulled the credential — never fails a Booking and is never erased behind the Host's back. The write falls back to the write target, the page says the choice is not in effect, and the stored value round-trips untouched through unrelated saves via the `keep` sentinel.

**Two defects found in the plan itself, both caught by implementers and upheld:**
- The plan's sample guard `!KEEP.equals(x)` cleared a live override whenever a POST omitted the field — and the field genuinely is omitted in normal browser use, because the `<select>` renders only when the Host has a selected calendar, which is exactly the disconnected Host whose override is dangling. Corrected to `x != null && !KEEP.equals(x)` on both pages and pinned by a test that fails against the original.
- The plan's briefs repeatedly used "default write target", which `CONTEXT.md` bans. Swept out of everything this branch authored.

**Verification:** full suite green after the fix wave. New-code line coverage 111/113 (98.2%). Cross-tenant resolution on the group-booking path and the never-erase guard are each pinned by tests verified to fail when the code is deliberately broken.

**Also landed:** [[calit-gsl7]] (merged in — a red suite blocks a PR, so the fixture repair was a prerequisite, not a side quest) and a `CONTEXT.md` clarification that the Meet gate follows the Creator's resolved calendar, not the organizer's.

**Follow-ups filed:** [[calit-szew]], plus six from the final review — the bare 400 on a Meet/non-Meet conflict, the co-host Meet gate, the missing plural form, the no-op moved-bookings notice, the `blocksMeet` no-calendar test, and the untested Google-failure retry path.

**Not done here:** the docs branch is committed but unpushed and its changelog still carries `#TBD` — it needs the real PR number before either branch is pushed.
