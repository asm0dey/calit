---
# calit-9d76
title: 'Meeting type create form: use the workplan working-hours grid'
status: in-progress
type: feature
priority: normal
created_at: 2026-08-15T21:14:55Z
updated_at: 2026-08-22T10:05:15Z
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
- [x] Docs: only if user-visible setup steps change (likely not) — no docs change needed, form-parity fix only
