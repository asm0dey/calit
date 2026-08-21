---
# calit-mqxf
title: No test for blocksMeet when the Host has no calendar at all
status: todo
type: task
priority: low
created_at: 2026-08-21T18:50:17Z
updated_at: 2026-08-21T18:50:17Z
---

`WriteTargetResolver.blocksMeet` deliberately returns false when the resolved calendar is null — a Host with no connected Google account, or no write target, must not be blocked from offering Google Meet. That "don't over-block" rule is a product decision with no test pinning it.

Cheap to add and worth having: the opposite behaviour (blocking when there is no calendar) would silently remove Google Meet from the location list for every Host in degraded mode.

Found in the final whole-branch review of [[calit-bh5t]].

- [ ] Assert blocksMeet is false for an owner with no connected account
- [ ] Assert it is false for an owner connected but with no write target
