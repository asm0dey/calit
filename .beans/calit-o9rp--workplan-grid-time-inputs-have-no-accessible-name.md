---
# calit-o9rp
title: Workplan grid time inputs have no accessible name
status: todo
type: bug
priority: low
created_at: 2026-08-22T10:22:11Z
updated_at: 2026-08-22T10:22:11Z
---

On all four workplan grids (`availability.html`, `meetingTypeDetail.html`, `meetingTypes.html`, `sharedAvailability.html`) the `frameStart` / `frameEnd` `<input type="time">` elements carry no label, no `aria-label` and no `aria-labelledby`. The only day context is a `<strong>` element that is not programmatically associated with the inputs, so a screen-reader user hears "time, time" repeated for every day with no idea which day or which end of the range they are editing.

Raised as an out-of-scope observation by the final code review on calit-9d76; app-wide pattern, not introduced there.

## Notes

- Cheapest fix is an `aria-label` per input naming both the day and the bound, e.g. "Monday start" / "Monday end" — needs an i18n message with a `{day}` placeholder, and de + he values in the same change.
- The frames added by `workplan.js` from `<template data-frame-template>` need the same treatment; the script sets `frameDay`'s value on clone, so it can set the labels there too.
- Check the date-override window inputs (`windowStart` / `windowEnd`) for the same gap while in there.

## Todo

- [ ] Give frameStart/frameEnd an accessible name naming the day and the bound
- [ ] Cover the JS-cloned frames, not just the server-seeded rows
- [ ] i18n key with de + he values
- [ ] `mvn test` green
