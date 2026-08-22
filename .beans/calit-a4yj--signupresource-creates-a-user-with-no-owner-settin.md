---
# calit-a4yj
title: SignupResource creates a user with no owner_settings row
status: completed
type: bug
priority: normal
created_at: 2026-08-21T21:43:01Z
updated_at: 2026-08-22T13:39:47Z
---

`SignupResource:76` persists the `AppUser` and stops. Every other creation path seeds the `owner_settings` row alongside it — `SetupResource:78`, `UsersResource:123`, `GoogleSignInService.provision` — because `owner_name`/`owner_email`/`timezone` are NOT NULL and the public booking path reads `OwnerSettings.forOwner(id).timezone` unguarded.

That unguarded read is exactly issue #99, which `V24__backfill_owner_settings.sql` fixed for rows that already existed. V24 is a one-shot backfill at boot, not a runtime guarantee, so a user who signs up on a running instance still has no settings row until they finish the first-login wizard.

Probably masked today: `MeOwnerFilter` will not let them reach `/me` before the wizard creates the row, and `MeetingHosts:135` refuses them as a co-host while `settingsComplete` is false. So the window is real but currently unreachable. The asymmetry is the bug — the next path that reads settings before onboarding completes hits the NPE.

Structural fix: one `provisionNewUser()` helper doing username normalize + user persist + settings placeholders + default availability, called by all five creation sites. That would also absorb the availability seeding that `calit-sjwh` put in the wizard.

Found while planning calit-sjwh (see `docs/superpowers/plans/2026-08-21-high-priority-issues.md`).

- [x] Confirm whether the window is genuinely unreachable — it is, today: MeOwnerFilter blocks /me before the wizard and MeetingHosts:135 refuses them as a co-host while settingsComplete is false. Fixed anyway; the asymmetry, not the reachability, was the bug.
- [x] NARROWED to OwnerSettings.seed(ownerId, email), routed through all four creation sites. A full provisionNewUser() would need five divergent params (admin flag, argon2 hash vs null hash vs Google sub) and branch internally — more code than it removes. Availability seeding stays in the first-onboarding wizard where calit-sjwh put it; moving it into account creation would re-open the disabled-owner exposure V28's enabled filter closed.
- [x] Test that a /signup user has an owner_settings row immediately after registering — SignupEnabledTest.postSeedsTheOwnerSettingsRow and OwnerSettingsForOwnerTest.seedWritesTheNotNullPlaceholders / seedTreatsANullEmailAsEmptyNotNull, all green.

## Summary of Changes

Added `OwnerSettings.seed(Long ownerId, String email)` and routed all four account-creation sites (SignupResource.register, SetupResource.createFirstUser, UsersResource.create, GoogleSignInService.provision) through it, replacing three copies of hand-built OwnerSettings construction plus fixing the missing seed in SignupResource. A fifth creation path, OidcSignInService.provision, was found during review — it already seeds owner_settings via its own duplicate copy (not broken, just left un-consolidated); the brief's four-site scope did not name it, so it was left untouched and flagged in the task report.
