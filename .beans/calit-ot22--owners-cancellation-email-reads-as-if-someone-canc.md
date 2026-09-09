---
# calit-ot22
title: 'Owner''s cancellation email reads as if someone cancelled on them (GH #198)'
status: completed
type: bug
priority: normal
created_at: 2026-09-09T22:29:38Z
updated_at: 2026-09-09T23:10:03Z
---

The owner's own copy of a cancellation mail reuses the invitee string "Your booking has been cancelled." — passive, and phrased for someone the cancellation happened to. Fix: a fourth message for the byOwner x owner branch ("You cancelled your meeting with {name}."), and reword the passive email_cancellation_body_owner to "{name} cancelled their booking."

Upstream: https://github.com/asm0dey/calit/issues/198
Plan: docs/superpowers/plans/2026-09-10-owner-cancellation-email-wording.md

## Todo
- [x] Task 1: new email_cancellation_body_owner_self key + cancellation.html branch + tests
- [x] Task 2: reword email_cancellation_body_owner to active voice + tests
- [x] Task 3: docs-site `## Unreleased` changelog entry

## Summary of Changes

- New `email_cancellation_body_owner_self(name)` message (en/de/he); the `byOwner` x `recipientRole == 'owner'` cell of `email/cancellation.html` now renders "You cancelled your meeting with {name}."
- `email_cancellation_body_owner` reworded from the passive "{name}'s booking was cancelled." to "{name} cancelled their booking." (en/de/he).
- `Templates.cancellation` gained one `boolean hostSelfCancel` param (= `byOwner && groupId == null`). Group bookings keep main's neutral wording: `byOwner` means *a* host cancelled, not *this* host, and `cancelFutureGroupBookingsForHost` calls `cancel(token, true)` on co-host removal, so without the narrowing every remaining host would have been told they cancelled.
- Tests at both levels (`EmailRoleCopyTest` template render, `EmailServiceTest` delivered mail incl. a two-host group case). A previously unfalsifiable assertion replaced with a positive one. Full suite 1070 green.
- PR #201 (code), PR #202 (docs-site changelog).
- Follow-ups: calit-my3s (group cancellation cannot name the actual canceller), calit-blpt (parity test ignores placeholder agreement).
