---
# calit-pwge
title: Can a no-JS owner add hours to an empty day?
status: todo
type: bug
priority: low
created_at: 2026-08-26T09:16:59Z
updated_at: 2026-08-26T09:16:59Z
---

Suspected, not confirmed. `_workplanGrid.html` renders frame inputs only for frames that already exist, so a day with none shows its buttons and no rows — visible in `docs-site/public/img/multi-host-shared-availability.png`, where Saturday and Sunday have buttons but nothing to type into.

Adding a frame is `workplan.js` cloning `[data-frame-template]` on `[data-add-frame]`. With JavaScript disabled that button does nothing, so if the observation holds, an owner without JavaScript cannot give hours to a day that has none — and progressive enhancement is a hard requirement in this project, not a preference.

The durations editor deliberately avoided this by always rendering one spare blank row alongside its `+ Duration` button ([[calit-he09]]); the same trick would fix this if it is real.

## Verify before fixing

Load `/me/availability` (and a shared type's availability page) with JavaScript disabled and try to give Saturday hours. If it works, close this and say how — I inferred the gap from markup and a screenshot, not from a browser.

## Todo

- [ ] Confirm or refute with JS disabled
- [ ] If real: render one blank frame row per empty day, mirroring the durations editor
- [ ] If real: check the same pattern in the other three pages that include the grid (`availability.html`, `meetingTypeDetail.html`, `sharedAvailability.html`, `meetingTypes.html`)
