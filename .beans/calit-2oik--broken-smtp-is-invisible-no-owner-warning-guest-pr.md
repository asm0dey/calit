---
# calit-2oik
title: 'Broken SMTP is invisible: no owner warning, guest promised an email that never arrives'
status: completed
type: feature
priority: high
created_at: 2026-09-10T22:14:57Z
updated_at: 2026-09-11T07:35:05Z
---

GH #195. A deployment with broken/unconfigured SMTP looks healthy: dashboard says nothing, copy-link toast is unconditionally green, the guest confirmation page unconditionally promises a confirmation email, and a failed send takes the .ics with it (no endpoint serves it). Plan: docs/superpowers/plans/2026-09-11-smtp-delivery-visibility.md

## Summary of Changes

Shipped as PR #207 on branch `feat/195-smtp-visibility` (merge-base 2bb6a3b0, head de1e5053).

New `MailHealth` bean (`site.asm0dey.calit.email`, CDI name `mailHealth`) is the single seam: a
60s-cached reachability state delegated to the existing `SmtpHealthCheck` so the banner can never
disagree with /q/health/ready, a dead-letter count over `email_outbox`, and `undeliveredFor(address)`.
Four surfaces read it — dashboard banner (unconfigured vs unreachable, plus a dead-letter count that
survives recovery), red copy-link toast via `{cdi:mailHealth.degraded}`, honest guest confirmation
copy, and a `GET /booking/{manageToken}/invite.ics` download backed by `EmailService.inviteeIcs`.
No new dependency, no new table, no migration. 1089 tests green (1070 on main, +19).

Two departures from the issue, both argued in the PR body:

1. No polling. The confirmation page renders in the same request as the booking commit —
   `bookingService.book` is \@Transactional, the mail observers are AFTER_SUCCESS (synchronous, on the
   committing thread), and MailSender parks failures before PublicResource returns the page. Proven
   live with a temporary outbox assertion plus a captured stack trace, and fenced by a test that
   fails if anyone adds \@Transactional to submitBooking. It also works with JS disabled; polling
   would not.
2. The .ics link is unconditional EXCEPT when Google is connected. Fully unconditional was my first
   call and review showed it wrong: calit sends no .ics on Google-connected instances because Google
   natively invites the guest, so an imported copy is stranded forever on a later reschedule or
   cancel. The link is hidden there; the endpoint stays open.

Known cost: `undeliveredFor` is keyed by recipient address, not booking id, because `email_outbox`
has no booking column. A guest who books twice during an outage sees the warning on both.

Docs: README `## Requirements` section on the feature branch; install + configuration pages and the
`## Unreleased` changelog bullet on `docs-site` (commits e03b1a6b, b5fdf82c).

German and Hebrew for all eight new strings were agent-drafted; #206 tracks a native read.

Follow-ups filed: calit-242z (no index on either new query, outbox never pruned), calit-0e98
("we'll keep trying" is false for an already-dead letter), calit-te3s (UNCONFIGURED vs UNREACHABLE
likely never distinguishes in prod, since quarkus.mailer.host defaults to localhost).
