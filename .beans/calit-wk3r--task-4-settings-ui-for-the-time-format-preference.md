---
# calit-wk3r
title: 'Task 4: Settings UI for the time-format preference'
status: completed
type: task
priority: normal
created_at: 2026-08-15T22:13:22Z
updated_at: 2026-08-15T22:16:08Z
---

Add a <select> on the owner settings page (settings.html) so a host can pick auto/h23/h12 time format, wired to OwnerSettings.timeFormat from Task 3. Add adm bundle messages + de/he translations. TDD: extend OwnerTimeFormatSettingTest.

## Todo
- [x] Add failing test settingsPageOffersAllThreeOptionsAndMarksTheSavedOne + containsString import
- [x] Run test, confirm RED
- [x] Add 4 @Message methods to AdminMessages.java
- [x] Add de translations to adm_de.properties
- [x] Add he translations to adm_he.properties
- [x] Add <select> markup to settings.html
- [x] Run test, confirm GREEN
- [x] Run regression: OwnerTimeFormatSettingTest,AdminI18nTest,SettingsLocaleTest,CsrfFormCoverageTest
- [x] Run translation parity check loop
- [x] Commit
- [x] Write task-4-report.md

## Summary of Changes
Added `name="timeFormat"` select (auto/h23/h12) to `settings.html` inside the existing CSRF-protected settings form, four new `AdminMessages` bundle methods with de/he translations copied verbatim from the task-4 brief, and a new test asserting all three options render with the saved one marked `selected`. Red run confirmed missing field; green run: 15/15 tests pass (AdminI18nTest, OwnerTimeFormatSettingTest, SettingsLocaleTest, CsrfFormCoverageTest); translation parity loop: 8/8 ok. `mvn spotless:check` clean. Committed as 536dd81. Full report at `.superpowers/sdd/task-4-report.md`.
