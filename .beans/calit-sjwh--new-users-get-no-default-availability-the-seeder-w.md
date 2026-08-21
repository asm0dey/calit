---
# calit-sjwh
title: New users get no default availability — the seeder was never wired to the first-login wizard
status: todo
type: bug
priority: high
created_at: 2026-08-21T18:56:14Z
updated_at: 2026-08-21T18:56:14Z
---

`DefaultAvailabilitySeeder` is dead code in production. Its `onStart` observer is `// intentionally no-op until Phase 4 wires per-owner seeding`, and `weekdayDefaults()` (Mon-Fri 09:00-18:00, global) is package-private with **no production caller** — `grep -rn 'DefaultAvailabilitySeeder|weekdayDefaults' src/main/java` finds only the class itself. Only `DefaultAvailabilitySeederTest` calls it.

The class javadoc says boot-time global seeding was disabled during multi-user Phase 2 because a rule needs an `owner_id` and no `app_user` exists at boot, and that "Phase 4's first-login wizard seeds each new owner's default availability". That wiring never landed.

**Consequence:** every new user starts with no global availability rules at all. Their meeting types offer no slots until they set working hours by hand, and the working-hours UI actively misleads them — its help text reads "Until you save them the grid shows your global default hours", but the grid is empty because there are no global defaults to show.

Found while capturing docs screenshots for [[calit-bh5t]]: two users created through the normal flow (admin via `/setup`, second user via `/me/users`), both completing `/me/setup`, and `select * from availability_rule where meeting_type_id is null` returned nothing for either.

- [ ] Decide where seeding belongs: the `/me/setup` first-login wizard, or user creation itself (so an admin-created user is seeded too)
- [ ] Wire `weekdayDefaults()` there, stamping `owner_id`
- [ ] Make it idempotent — completing the wizard twice must not double the rules
- [ ] Test that a newly created user has bookable hours without touching the availability editor
- [ ] Confirm the working-hours help text is true once defaults exist, or reword it
