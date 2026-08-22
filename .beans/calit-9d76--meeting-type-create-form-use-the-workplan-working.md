---
# calit-9d76
title: 'Meeting type create form: use the workplan working-hours grid'
status: completed
type: feature
priority: normal
created_at: 2026-08-15T21:14:55Z
updated_at: 2026-08-22T10:23:22Z
---

Upstream: https://github.com/asm0dey/calit/issues/120 (reporter confirmed the *edit* page UI is the one to keep).

The meeting-type **create** form (`templates/AdminResource/meetingTypes.html:107-124`) has a stripped-down working-hours block: seven fixed rows, one `ruleStart`/`ruleEnd` pair per weekday, no add-frame, no copy buttons. The **edit** page (`meetingTypeDetail.html:107-153`) and `/me/availability` both use the richer workplan grid: per-day cards with multiple frames, "add frame", "copy to all days", "copy to weekdays", remove-frame, driven by `META-INF/resources/workplan.js` on `[data-workplan]` + `frameDay`/`frameStart`/`frameEnd`.

Make the create form use the same grid.

## Notes

- Server side: `AdminResource.java:475-495` parses `ruleDay`/`ruleStart`/`ruleEnd` (one row per day). The workplan variant is already implemented at `AdminResource.java:1044+` (`frameDay`/`frameStart`/`frameEnd`, multiple frames per day) — reuse it instead of keeping two parsers.
- The create page is one big `<form>`; `workplan.js` scopes buttons via `closest("[data-workplan]")`, so the attribute goes on the create form itself. Verify the add/copy buttons don't submit the form (they are `type="button"` in the existing markup — keep that).
- Progressive enhancement invariant: without JS the grid must still submit the pre-rendered frames. Seed one empty frame row per day server-side.
- i18n: `adm_detail_frame_add` / `adm_detail_copy_all` / `adm_detail_copy_weekdays` / `adm_detail_to` / `adm_detail_remove_frame_aria` exist. Either reuse them or add `adm_meetingTypes_*` twins — new keys need `de` + `he` values in `messages/adm_{de,he}.properties` in the same change.
- The date-override block on the create form (`meetingTypes.html:126-140`) is a separate concern; leave it unless it falls out for free.

## Todo

- [x] Replace the ruleDay/ruleStart/ruleEnd block in `meetingTypes.html` with the workplan grid markup (+ `<template data-frame-template>`, `<script src="/workplan.js">`)
- [x] Point the create handler at the frame parser; delete the now-dead `ruleDay` parsing path
- [x] i18n keys resolved (reused or added with de + he)
- [x] Test: creating a meeting type with multiple frames on one day persists both AvailabilityRules
- [x] Test: no-JS submit of the default rendered form still creates the expected rules
- [x] `mvn spotless:apply` + `mvn test`
- [x] Docs: no docs-site page change needed (no env var, route, config flag or setup step moved); a `## Unreleased` changelog bullet DID land on the `docs-site` branch (commit a950dda, PR link left as #TBD)

## Summary of Changes

The meeting-type create form now renders the same workplan working-hours grid as the edit page and `/me/availability`: per-day cards, multiple frames per day, `+ Frame`, copy-to-all-days, copy-to-weekdays, clear-day, driven by the existing generic `workplan.js` via `data-workplan` on the create form itself.

- `meetingTypes.html` — the seven fixed `ruleDay`/`ruleStart`/`ruleEnd` rows are replaced by the grid, a `<template data-frame-template>` and `<script src="/workplan.js">`. One blank frame per weekday is seeded server-side, so a JS-off browser posts exactly what the old form posted and the progressive-enhancement invariant holds.
- `AdminResource` — `createMeetingType` now calls the shared `persistFrames(ownerId, typeId, form)`; the second parser `createInitialWorkingHours` is deleted. Nothing in `src/main` reads `ruleDay`/`ruleStart`/`ruleEnd` any more. As a side effect the create endpoint is hardened: `persistFrames` drops unparseable days/times and inverted frames where the old parser would have 500'd or persisted them.
- `createInitialDateOverride` was re-ordered to `(ownerId, typeId, form)` so both helpers on that path read the same way — the old `(typeId, ownerId)` next to `persistFrames(ownerId, typeId)` was a compile-clean cross-tenant footgun.
- Four `adm_meetingTypes_*` i18n keys added with de + he values copied verbatim from their `adm_detail_*` twins.
- Tests: four in `AdminMeetingTypeFormTest` — rendered-markup markers (incl. that a seeded row carries its own day), blank-frame skip, multiple frames on one day plus an inverted frame dropped, and a no-JS submit of all seven seeded rows. Full suite 900/900 green.

Not done here, filed as follow-ups: [[calit-spfu]] shared workplan Qute fragment + i18n key consolidation, [[calit-8nlx]] unguarded `overrideDate` parse 500, [[calit-o9rp]] missing accessible names on the grid time inputs. The date-override block on the create form is untouched, as this bean specified.
