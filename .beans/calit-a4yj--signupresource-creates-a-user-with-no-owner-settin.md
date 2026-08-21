---
# calit-a4yj
title: SignupResource creates a user with no owner_settings row
status: todo
type: bug
priority: normal
created_at: 2026-08-21T21:43:01Z
updated_at: 2026-08-21T21:43:01Z
---

`SignupResource:76` persists the `AppUser` and stops. Every other creation path seeds the `owner_settings` row alongside it — `SetupResource:78`, `UsersResource:123`, `GoogleSignInService.provision` — because `owner_name`/`owner_email`/`timezone` are NOT NULL and the public booking path reads `OwnerSettings.forOwner(id).timezone` unguarded.

That unguarded read is exactly issue #99, which `V24__backfill_owner_settings.sql` fixed for rows that already existed. V24 is a one-shot backfill at boot, not a runtime guarantee, so a user who signs up on a running instance still has no settings row until they finish the first-login wizard.

Probably masked today: `MeOwnerFilter` will not let them reach `/me` before the wizard creates the row, and `MeetingHosts:135` refuses them as a co-host while `settingsComplete` is false. So the window is real but currently unreachable. The asymmetry is the bug — the next path that reads settings before onboarding completes hits the NPE.

Structural fix: one `provisionNewUser()` helper doing username normalize + user persist + settings placeholders + default availability, called by all five creation sites. That would also absorb the availability seeding that `calit-sjwh` put in the wizard.

Found while planning calit-sjwh (see `docs/superpowers/plans/2026-08-21-high-priority-issues.md`).

- [ ] Confirm whether the window is genuinely unreachable, or find the path that reaches it
- [ ] Extract provisionNewUser() and route all five creation sites through it
- [ ] Test that a /signup user has an owner_settings row immediately after registering
