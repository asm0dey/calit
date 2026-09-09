---
# calit-ibpe
title: 'Owner emails don''t include the invitee''s email address (GH #196)'
status: in-progress
type: bug
priority: normal
created_at: 2026-09-09T21:35:15Z
updated_at: 2026-09-09T22:02:06Z
---

Owner-facing booking emails name the invitee but never show their address, so the owner cannot reply/forward/look them up without opening the admin UI. Fix: pass `inviteeEmail` into the 7 owner-facing templates and render an `Invitee:` line (mailto-linked) in the owner branch.

Upstream: https://github.com/asm0dey/calit/issues/196
Plan: docs/superpowers/plans/2026-09-09-owner-emails-invitee-address.md

Scope decision: body line only. `Reply-To: <invitee>` (floated in the issue as "a nice complement") is OUT of scope — it needs a MailSink/MailSender signature change plus an `email_outbox` column, or a retried mail silently loses the header.

## Todo
- [x] Task 1: label + shared `_invitee.html` partial + confirmation.html slice
- [x] Task 2: roll out to requested/reminder/reschedule/updated/declined/cancellation
- [ ] Task 3: docs-site `## Unreleased` changelog entry
