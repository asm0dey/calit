---
# calit-syal
title: Fix no-JS admin fallback readability + settings label wording
status: completed
type: task
priority: normal
created_at: 2026-08-15T23:09:03Z
updated_at: 2026-08-15T23:26:22Z
---

Task 8: (1) add DisplayExtensions.when(Instant, zoneId) and wire zone param through dashboard.html/pending.html so no-JS fallback shows human-readable date+zone instead of raw ISO. (2) reword adm_settings_time_format_auto to 'Automatic' (was 'Automatic (from your device)') across en/de/he. TDD: AdminTimeRenderingTest first (red), then implement.


## Summary of Changes

- Added `DisplayExtensions.when(Instant, String zoneId)` (namespace `display`): null-safe,
  falls back to `ZoneId.of("UTC")` on a bad/blank/null zone id instead of throwing
  `DateTimeException`. Reuses the manage-booking date pattern
  `EEEE, d MMMM yyyy 'at' HH:mm (z)`.
- Wired a `{@java.lang.String zone}` param through `dashboard.html`/`pending.html` and all
  four `AdminResource` call sites (`dashboard()`, `pending()`, `approveBooking()`,
  `declineBooking()`) via a new `ownerZone()` helper scoped to `currentOwner.id()`.
- Reworded `adm_settings_time_format_auto` from "Automatic (from your device)" to
  "Automatic" in en/de/he (all three locales updated in the same commit).
- TDD: red step confirmed (compile failure — `when(...)` didn't exist yet), then implemented;
  targeted regression set green (33/33); full suite green (801/801); spotless:check clean.
- Full report at .superpowers/sdd/task-8-report.md (gitignored, not committed).
