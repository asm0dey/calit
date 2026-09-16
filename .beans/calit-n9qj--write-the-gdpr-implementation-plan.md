---
# calit-n9qj
title: Write the GDPR implementation plan
status: completed
type: task
priority: normal
created_at: 2026-09-16T12:21:42Z
updated_at: 2026-09-16T12:31:47Z
parent: calit-l3fk
---

Turn docs/superpowers/specs/2026-09-12-gdpr-compliance-design.md into a task-by-task implementation plan under docs/superpowers/plans/.

## Summary of Changes

Wrote `docs/superpowers/plans/2026-09-16-gdpr-compliance.md` — 12 tasks across 4 PRs on one `gdpr-compliance` branch, each task TDD-shaped (failing test -> minimal impl -> full suite -> commit).

Three spec deviations found against current `main` and resolved in the plan:
1. V33 is taken (`V33__notification_channel_default_enabled.sql`); migration is V34.
2. No Google OAuth revoke exists — `GooglePageResource.disconnect` only deletes the credential row. Account deletion therefore removes calit's token copy without withdrawing the grant; documented in the policy copy and operator guide.
3. `booking.title`/`description` are invitee-writable via `POST /booking/{t}/edit-details`, so they are personal columns the spec's table omitted. Classified personal and nulled on erasure.
