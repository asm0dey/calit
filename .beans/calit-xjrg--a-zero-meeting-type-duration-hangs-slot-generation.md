---
# calit-xjrg
title: A zero meeting-type duration hangs slot generation
status: todo
type: bug
priority: high
created_at: 2026-08-26T08:43:38Z
updated_at: 2026-08-26T08:43:38Z
---

Setting a meeting type's **Duration** to `0` spins slot generation forever and pins the request thread. Reachable through the ordinary owner UI — no crafted request needed.

## Why nothing stops it

Three guards that each look like they would, and none does:

| Layer | State |
|---|---|
| HTML input | `AdminResource/meetingTypeDetail.html` — the Basics field is `type="number" ... required` with **no `min`** (the durations table's own inputs DO carry `min="1"`) |
| Server | `AdminResource.applyEditableFields:488` — `t.durationMinutes = durationMinutes;`, straight from `@RestForm int`, unvalidated |
| Database | `V1__core_schema.sql:12` — `duration_minutes INT NOT NULL`, **no CHECK**. `V29`'s child table has `check (duration_minutes > 0)`, so the extra lengths are constrained and the type's own is not |

## The hang

`SlotService` derives the cadence from `MeetingTypeDuration.shortestAllowed(type)`, which is `min(rows ∪ {type.durationMinutes})` — so a zero default makes the step zero, and both branches loop forever:

- window-anchored: `s = s.plus(gap)` with `gap` of zero never advances, and the `isAfter(windowEnd)` test never becomes true
- lattice-anchored: `for (minute = 0; minute < 1440; minute += step)` with `step` zero

Every public booking page render, every `availableSlots` call, and the reschedule pages all route through it. Repeated hits exhaust the request pool, so one owner's typo degrades the whole instance.

## Not introduced by this branch

Before selectable durations the step already fell back to `durationMinutes` via `MeetingType.effectiveSlotIntervalMinutes()`, so the same zero produced the same hang. The final review of `calit-p5xm` flagged it and I recorded it as a non-blocking minor on the grounds that it was 'not reachable today' — that was wrong, and this bean exists to correct it: the Basics field has no `min`, so it is reachable by typing.

## Fix

- `min="1"` on the Basics duration input, matching the durations table
- reject `<= 0` server-side in `applyEditableFields` — the HTML attribute is a hint, not a guard
- a Flyway CHECK on `meeting_type.duration_minutes`, mirroring `V29`. **Check for existing zero/negative rows first** — a CHECK that fails validation at boot takes the app down instead of the request
- consider `Math.max(1, step)` in `SlotService` as a belt-and-braces guard against an infinite loop from any future path, since the cost of being wrong there is a pinned thread rather than a wrong number

## Todo

- [ ] Confirm the hang against a running instance (set 0, load the public page) before fixing, so the fix is verified against the real symptom
- [ ] `min="1"` + server-side rejection
- [ ] Migration CHECK, after auditing existing rows
- [ ] Guard in SlotService
- [ ] A test that a zero duration is refused at save
- [ ] i18n for whatever the rejection tells the owner, with `de` + `he`
