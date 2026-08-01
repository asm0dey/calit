---
# calit-9h87
title: 'Booking 500: first /setup user has no OwnerSettings row'
status: in-progress
type: bug
created_at: 2026-08-01T15:33:36Z
updated_at: 2026-08-01T15:33:36Z
---

Issue #99: public booking POST 500s (NPE) when the owner has no owner_settings row. SetupResource creates the first admin AppUser but not its OwnerSettings; every other creation path (UsersResource invite, Google/OIDC sign-in) seeds one. BookingService.enforcePerEmailDailyCap/assertSlotAvailable/hostFreeSlots read OwnerSettings.forOwner(id).timezone unguarded -> NPE -> 500 (uncaught by submitBooking's catch block).

Fix:
- [ ] Seed OwnerSettings in SetupResource after creating first user (placeholders, wizard overwrites)
- [ ] V24 backfill migration: insert owner_settings for any app_user lacking one (fixes already-broken installs like the reporter's)
- [ ] Run booking + setup tests
- [ ] Cut minor release
- [ ] Draft reporter comment for #99 (await user review before posting)
