---
# calit-9h87
title: 'Booking 500: first /setup user has no OwnerSettings row'
status: completed
type: bug
priority: normal
created_at: 2026-08-01T15:33:36Z
updated_at: 2026-08-01T15:57:45Z
---

Issue #99: public booking POST 500s (NPE) when the owner has no owner_settings row. SetupResource creates the first admin AppUser but not its OwnerSettings; every other creation path (UsersResource invite, Google/OIDC sign-in) seeds one. BookingService.enforcePerEmailDailyCap/assertSlotAvailable/hostFreeSlots read OwnerSettings.forOwner(id).timezone unguarded -> NPE -> 500 (uncaught by submitBooking's catch block).

Fix:
- [x] Seed OwnerSettings in SetupResource after creating first user (placeholders, wizard overwrites)
- [x] V24 backfill migration: insert owner_settings for any app_user lacking one (fixes already-broken installs like the reporter's)
- [x] Run booking + setup tests
- [x] Cut minor release
- [x] Draft reporter comment for #99 (await user review before posting)

## Summary of Changes

- `SetupResource`: seed an `OwnerSettings` placeholder row (ownerName/ownerEmail `""`, timezone `UTC`) right after creating the first admin user, matching the invite/Google/OIDC paths.
- `V24__backfill_owner_settings.sql`: insert a placeholder `owner_settings` row for every `app_user` lacking one — fixes already-broken installs on upgrade.
- `SetupFlowTest.setupSeedsOwnerSettingsSoBookingWontNpe`: regression test. Full suite green (776 tests).
- Released **1.19.0** (commit `d1f4d29`, tag `v1.19.0`, pushed to main; CI published image + GH release). docs-site changelog added.
- Posted comment on #99 (https://github.com/asm0dey/calit/issues/99#issuecomment-5152193092), flagged as a *likely* fix — exact cause of the reporter's 500 unconfirmed without their stack trace.

Note: hypothesis not confirmed against the reporter's actual trace; if it recurs on 1.19.0 they'll reopen with the full stack trace.
