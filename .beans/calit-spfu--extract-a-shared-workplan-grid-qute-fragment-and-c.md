---
# calit-spfu
title: Extract a shared workplan grid Qute fragment (and collapse its duplicated i18n keys)
status: completed
type: task
priority: normal
created_at: 2026-08-22T10:21:36Z
updated_at: 2026-08-22T11:13:47Z
---

The workplan working-hours grid markup now exists verbatim in four Qute templates: `AdminResource/availability.html`, `AdminResource/meetingTypeDetail.html`, `AdminResource/meetingTypes.html` (added by calit-9d76) and `SharedMeetingsResource/sharedAvailability.html`. Every one binds the same `workplan.js` contract (`data-workplan`, `data-day`, `data-frames`, `data-frame`, `data-frame-template`, `data-add-frame`, `data-remove-frame`, `data-copy-all`, `data-copy-weekdays`, `data-clear-day`). Any future change to that contract needs four synchronized edits.

Raised by the final code review on calit-9d76, which also disputed the per-page i18n twin convention: `AdminMessages` now carries three verbatim copies of the same four strings (`adm_detail_frame_add`, `adm_availability_frame_add`, `adm_meetingTypes_frame_add`, and the copy_all / copy_weekdays / remove_frame_aria families), i.e. twelve properties lines of identical text a translator must keep in sync across de and he. `adm_workplan_clear_day` already exists as the shared family for exactly this grid, and `sharedAvailability.html` already reuses `adm_detail_*` across pages — so cross-page reuse is established practice, not a departure.

## Notes

- A Qute `{#include}` taking the day list and an i18n key prefix collapses the markup duplication and the key duplication in one move.
- The pages differ in form scope, form action and i18n namespace — that is what the parameters are for.
- No behaviour change; the tests pinning the `data-*` markers on each page (`AdminMeetingTypeFormTest.createFormRendersWorkplanGrid`, `AdminTypeHoursPrefillTest.everyDayRowOffersRemoveAvailability`) are the regression net.
- Removing an `@Message` method means removing its key from both `adm_de.properties` and `adm_he.properties` in the same change, or `MultiHostMessageParityTest.adminPropertyFilesHaveNoOrphanKeys` fails.

## Todo

- [x] Extract the grid into one Qute fragment parameterised by day list + i18n key prefix (or a single shared key family)
- [x] Point all four templates at it
- [x] Collapse the duplicated adm_detail_/adm_availability_/adm_meetingTypes_ frame keys onto one family, removing orphans from de + he
- [x] `mvn test` green

## Summary of Changes

- New shared partial `src/main/resources/templates/AdminResource/_workplanGrid.html` (following the `_copyToast.html`/`_meetingtypecard.html` underscore-partial convention): renders the day cards (`data-day`, the four button row, `data-frames`) and the single `<template data-frame-template>`, parameterised by `week` (`List<WeekRow>`) and `cardClass` (the card background utility class). The including page still owns the `<form>` (`data-workplan`, action, CSRF) — the fragment only renders what's inside it.
- All four templates (`availability.html`, `meetingTypeDetail.html`, `sharedAvailability.html`, `meetingTypes.html`) now `{#include AdminResource/_workplanGrid week=... cardClass=... /}` instead of carrying their own copy of the grid markup.
- Create form's rows: added `WeekRow.blank()` in `WeekRow.java` — seven rows (`DayOfWeek.values()` order), each with one unpersisted `AvailabilityRule` whose `startTime`/`endTime` are left null. The fragment guards frame-value rendering with `{#if fr.startTime}{fr.startTime}{/if}` (and same for `endTime`), so a null renders as `value=""`, never the literal text "null" — verified with a throwaway RestAssured test (not part of the committed diff) asserting the create page's body has no `value="null"` and does have `name="frameStart" value=""`/`name="frameEnd" value=""`.
- `AdminResource.Templates.meetingTypes(...)` gained a `List<WeekRow> week` parameter; `renderMeetingTypes(String error)` passes `WeekRow.blank()`. The pre-existing (already-unused-on-3-of-4-pages) `daysOfWeek` param was left untouched — out of scope for this refactor.
- i18n: added `adm_workplan_frame_add`, `adm_workplan_copy_all`, `adm_workplan_copy_weekdays`, `adm_workplan_remove_frame_aria`, `adm_workplan_to` next to the existing `adm_workplan_clear_day` in `AdminMessages.java` + `adm_de.properties` + `adm_he.properties` (values copied verbatim from the removed twins). Removed the now-orphaned `adm_meetingTypes_frame_add/copy_all/copy_weekdays/remove_frame_aria`, `adm_detail_frame_add/copy_all/copy_weekdays/remove_frame_aria`, and `adm_availability_frame_add/copy_all/copy_weekdays/remove_frame_aria/to` (13 keys total) from all three places. Kept `adm_detail_to` and `adm_meetingTypes_to` — still used by the date-override window rows.
- Full suite: `mvn test` → 905 tests, 0 failures, 0 errors, 0 skipped. No test file was edited to accommodate the refactor.
