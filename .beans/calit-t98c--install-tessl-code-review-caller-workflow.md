---
# calit-t98c
title: Install Tessl Code Review caller workflow
status: in-progress
type: task
priority: normal
created_at: 2026-08-21T15:25:32Z
updated_at: 2026-08-21T15:30:09Z
---

Add .github/workflows/tessl-code-review.yml calling tesslio/code-review-action.

- [x] Detect repo conventions and existing callers
- [x] Interview: cadence + advisory/gate (ready-once + mentions, advisory)
- [x] Propose workflow for approval
- [x] Write workflow file
- [x] Verify YAML, uses ref, permissions, concurrency, single caller
- [ ] Confirm TESSL_TOKEN secret exists or tell user to create it
