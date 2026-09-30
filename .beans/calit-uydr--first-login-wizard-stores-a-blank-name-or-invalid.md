---
# calit-uydr
title: First-login wizard stores a blank name or invalid email
status: completed
type: bug
priority: normal
created_at: 2026-09-29T18:51:40Z
updated_at: 2026-09-30T09:14:34Z
---

Spec: UC-009 A4. MeSetupResource.submit (web/MeSetupResource.java:104-105) assigns ownerName/ownerEmail unchecked, then sets settingsComplete. Missing field -> NOT NULL violation -> 500. AdminResource.updateSettings (:1695-1696) has the same gap. Reuse an email check (BookingService.isPlausibleEmail / UsersResource.looksLikeEmail are private).

- [x] Validate blank name and email format in MeSetupResource.submit, re-render with error
- [x] Same in AdminResource.updateSettings
- [x] Tests

## Summary of Changes

`MeSetupResource.ownerDetailsError` (blank name / `UsersResource.looksLikeEmail`) guards both the wizard and `/me/settings`; errors re-render the form (new `owner_error_name_blank` key, de/he; settings page gained a `settingsError` slot). Tests in MeSetupResourceTest and AdminSettingsTest.
