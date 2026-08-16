---
# calit-184b
title: 'Task 3: Persist host''s 12h/24h time-format preference'
status: completed
type: task
priority: normal
created_at: 2026-08-15T22:05:35Z
updated_at: 2026-08-15T22:08:05Z
---

Flyway migration V25, OwnerSettings.timeFormat field + HOUR_CYCLES set, AdminResource form handler with null/invalid-value fallback to auto. Storage layer only, no UI/rendering (Tasks 4-6).



## Summary of Changes
- Added V25__owner_time_format.sql (owner_settings.time_format varchar(8) NOT NULL DEFAULT 'auto')
- Added OwnerSettings.timeFormat field + HOUR_CYCLES set (auto/h12/h23)
- AdminResource#updateSettings accepts @RestForm timeFormat, validates against HOUR_CYCLES,
  guards null explicitly (Set.of(...).contains(null) throws NPE - not in the brief's literal
  snippet, discovered via regression run against AdminSettingsTest/OwnerLocaleSettingTest which
  post without timeFormat).
- New test OwnerTimeFormatSettingTest: 3/3 green. Regression: AdminSettingsTest 2/2,
  OwnerLocaleSettingTest 3/3, all green (8/8 total).
- TDD red step confirmed: compile error 'cannot find symbol: variable timeFormat'.
