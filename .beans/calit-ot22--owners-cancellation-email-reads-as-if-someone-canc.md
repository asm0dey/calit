---
# calit-ot22
title: 'Owner''s cancellation email reads as if someone cancelled on them (GH #198)'
status: in-progress
type: bug
priority: normal
created_at: 2026-09-09T22:29:38Z
updated_at: 2026-09-09T22:36:45Z
---

The owner's own copy of a cancellation mail reuses the invitee string "Your booking has been cancelled." — passive, and phrased for someone the cancellation happened to. Fix: a fourth message for the byOwner x owner branch ("You cancelled your meeting with {name}."), and reword the passive email_cancellation_body_owner to "{name} cancelled their booking."

Upstream: https://github.com/asm0dey/calit/issues/198
Plan: docs/superpowers/plans/2026-09-10-owner-cancellation-email-wording.md

## Todo
- [x] Task 1: new email_cancellation_body_owner_self key + cancellation.html branch + tests
- [ ] Task 2: reword email_cancellation_body_owner to active voice + tests
- [ ] Task 3: docs-site `## Unreleased` changelog entry
