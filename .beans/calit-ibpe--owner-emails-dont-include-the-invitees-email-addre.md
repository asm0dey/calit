---
# calit-ibpe
title: 'Owner emails don''t include the invitee''s email address (GH #196)'
status: completed
type: bug
priority: normal
created_at: 2026-09-09T21:35:15Z
updated_at: 2026-09-09T22:10:38Z
---

Owner-facing booking emails name the invitee but never show their address, so the owner cannot reply/forward/look them up without opening the admin UI. Fix: pass `inviteeEmail` into the 7 owner-facing templates and render an `Invitee:` line (mailto-linked) in the owner branch.

Upstream: https://github.com/asm0dey/calit/issues/196
Plan: docs/superpowers/plans/2026-09-09-owner-emails-invitee-address.md

Scope decision: body line only. `Reply-To: <invitee>` (floated in the issue as "a nice complement") is OUT of scope — it needs a MailSink/MailSender signature change plus an `email_outbox` column, or a retried mail silently loses the header.

## Todo
- [x] Task 1: label + shared `_invitee.html` partial + confirmation.html slice
- [x] Task 2: roll out to requested/reminder/reschedule/updated/declined/cancellation
- [x] Task 3: docs-site `## Unreleased` changelog entry

## Summary of Changes

Added `AppMessages.email_body_invitee_label()` (en `Invitee:` / de `Eingeladener:` / he `מוזמן:`) and a shared `templates/email/_invitee.html` partial that renders `Invitee: <name> (<mailto link>)` only when `recipientRole == 'owner'`. Included it as the first `<li>` in all seven owner-facing templates (confirmation, requested, reminder, reschedule, updated, declined, cancellation), added an `inviteeEmail` parameter immediately after `inviteeName` on each `@CheckedTemplate` native method, and passed `l.booking.inviteeEmail` at all eight call sites in `EmailService`.

Covered by `EmailRoleCopyTest` (owner copy shows the mailto line; invitee copy does not) and an `EmailServiceTest` case asserting the address survives the real send path to the owner and never appears on the invitee copy. Full suite 1064/1064. A reviewer audited argument order at all six new call sites against their own declared parameter lists — no swap.

Changelog bullet committed on the `docs-site` branch as d19a3fba under `## Unreleased` (not pushed — awaiting the human, since pushing that branch deploys GitHub Pages).

`Reply-To: <invitee>` was deliberately left out: it would need a `replyTo` threaded through `EmailService.MailSink` and `MailSender`, plus an `email_outbox` column, or a retried mail would silently drop the header.
