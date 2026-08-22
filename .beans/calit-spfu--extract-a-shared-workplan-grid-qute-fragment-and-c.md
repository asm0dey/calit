---
# calit-spfu
title: Extract a shared workplan grid Qute fragment (and collapse its duplicated i18n keys)
status: todo
type: task
priority: normal
created_at: 2026-08-22T10:21:36Z
updated_at: 2026-08-22T10:21:36Z
---

The workplan working-hours grid markup now exists verbatim in four Qute templates: `AdminResource/availability.html`, `AdminResource/meetingTypeDetail.html`, `AdminResource/meetingTypes.html` (added by calit-9d76) and `SharedMeetingsResource/sharedAvailability.html`. Every one binds the same `workplan.js` contract (`data-workplan`, `data-day`, `data-frames`, `data-frame`, `data-frame-template`, `data-add-frame`, `data-remove-frame`, `data-copy-all`, `data-copy-weekdays`, `data-clear-day`). Any future change to that contract needs four synchronized edits.

Raised by the final code review on calit-9d76, which also disputed the per-page i18n twin convention: `AdminMessages` now carries three verbatim copies of the same four strings (`adm_detail_frame_add`, `adm_availability_frame_add`, `adm_meetingTypes_frame_add`, and the copy_all / copy_weekdays / remove_frame_aria families), i.e. twelve properties lines of identical text a translator must keep in sync across de and he. `adm_workplan_clear_day` already exists as the shared family for exactly this grid, and `sharedAvailability.html` already reuses `adm_detail_*` across pages — so cross-page reuse is established practice, not a departure.

## Notes

- A Qute `{#include}` taking the day list and an i18n key prefix collapses the markup duplication and the key duplication in one move.
- The pages differ in form scope, form action and i18n namespace — that is what the parameters are for.
- No behaviour change; the tests pinning the `data-*` markers on each page (`AdminMeetingTypeFormTest.createFormRendersWorkplanGrid`, `AdminTypeHoursPrefillTest.everyDayRowOffersRemoveAvailability`) are the regression net.
- Removing an `@Message` method means removing its key from both `adm_de.properties` and `adm_he.properties` in the same change, or `MultiHostMessageParityTest.adminPropertyFilesHaveNoOrphanKeys` fails.

## Todo

- [ ] Extract the grid into one Qute fragment parameterised by day list + i18n key prefix (or a single shared key family)
- [ ] Point all four templates at it
- [ ] Collapse the duplicated adm_detail_/adm_availability_/adm_meetingTypes_ frame keys onto one family, removing orphans from de + he
- [ ] `mvn test` green
