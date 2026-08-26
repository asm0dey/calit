---
# calit-he09
title: Add duration rows dynamically, like the working-hours grid
status: completed
type: feature
priority: normal
created_at: 2026-08-26T05:34:00Z
updated_at: 2026-08-26T05:54:35Z
---

The allowed-durations editor grows one row per save: it renders one row per allowed length plus a single blank spare, so adding a second length means saving and coming back. The working-hours grid already solves this with an **+ Add frame** button that clones a row client-side, and durations should match that idiom.

Follow-up to [[calit-p5xm]].

## Why it fits cleanly

The two editors already post the same shape — parallel arrays paired positionally:

| | working hours | durations |
|---|---|---|
| fields | `frameDay` / `frameStart` / `frameEnd` | `d.duration` / `d.before` / `d.after` |
| save | delete-all-then-insert, bulk | delete-all-then-insert, bulk |
| blank row | dropped | dropped (`parsePositive` returns null) |

So the server side needs **no change at all**. Adding N rows client-side just posts N more triples, and blank ones are already discarded.

## Shape

Follow `_workplanGrid.html` + `workplan.js` exactly rather than inventing a second pattern:

- a `<template data-duration-template>` holding one blank row
- a `[data-add-duration]` button that clones it into the rows container
- a `[data-remove-duration]` per row (today removal is 'clear the duration field and save', which stays valid)
- bind it the same way `workplan.js` binds, reusing its marker-attribute style

`workplan.js` is 97 lines and its contract is documented at the top of `_workplanGrid.html`: the markers ARE the contract across four including pages. Either extend that file or add a sibling with the same discipline — do not inline a script in the template.

## Progressive enhancement is the interesting constraint

**Keep the blank spare row.** It is not redundant with the button — it is the no-JS path, and JS-optional is a hard project requirement.

Worth noting the working-hours grid is arguably WEAKER here: a day with no frames renders no frame inputs, so without JS there is no way to add one (visible in `multi-host-shared-availability.png`, where Saturday and Sunday show buttons but no rows). The durations editor should not copy that gap. With both, a no-JS owner adds one length per save and a JS owner adds several before saving — strictly better than either alone.

## Out of scope

- Reordering rows. Order is by `duration_minutes`; there is no `position` column and ADR-0003 says there should not be one.
- Any server-side change. If this needs one, the client-side shape is wrong.

## Todo

- [ ] `<template data-duration-template>` + add/remove buttons in `meetingTypeDetail.html`
- [ ] Bind them, following `workplan.js`'s marker style
- [ ] Keep the blank spare row so the no-JS path still adds one per save
- [ ] i18n for the new button labels, with `de` + `he`
- [ ] Test that the no-JS path still works (RestAssured cannot run JS, so this is the default assertion)
- [ ] Check whether the working-hours no-JS gap is real, and file it separately if so

## Summary of Changes

Implemented on the `selectable-booking-duration` branch rather than deferred — an editor where adding a second length means save-and-come-back is a draft, not a feature.

- `durations.js`, modelled on `workplan.js`: delegated click handler scoped to `[data-durations]`, cloning `[data-duration-template]` on `[data-add-duration]` and dropping a row on `[data-remove-duration]`.
- The trailing blank row **stays** — it is the no-JS path, so an owner without JavaScript still adds one length per save.
- Removing the last remaining row blanks it instead of deleting it, so the owner is never left with no way to add a length.
- **A Default radio column**, which was not in the original scope: the default previously lived only in the Basics Duration field, so choosing which length an invitee sees first meant editing a different section.

The radio carries the row **index**, not a duration. That is what lets a length typed into the blank spare be made default in the *same* save — at render time that row has no value for the server to match on. `durations.js` renumbers after every add and remove, or the radio would point at a shifted or deleted row.

Server-side this is one edit to `meeting_type.duration_minutes` (ADR-0003 unchanged: the default is still an implicit member of the set), so the previous default keeps its row and moving the default never drops a length.

Verified in a browser, not only by tests: two lengths added in one save; a brand-new 90 made default in that same save, with 60 surviving as an ordinary length; an explicit `0` buffer round-tripping as zero rather than null.

Full suite 980/980.
