---
# calit-664k
title: 'Task 10: Hygiene purges'
status: completed
type: task
priority: normal
created_at: 2026-09-16T18:12:54Z
updated_at: 2026-09-16T18:17:59Z
parent: calit-l3fk
---

Purge stale parked mail from email_outbox and expired auth tokens (PasswordResetToken, LoginTicket) via a daily PurgeScheduler.

## Checklist

- [x] `TokenFixtures` in `src/test/java/site/asm0dey/calit/privacy/` (reset-token + login-ticket seeding/counting)
- [x] `PurgeSchedulerTest` in `src/test/java/site/asm0dey/calit/scheduler/` (brief's 5 tests + young-dead-row-survives + login-ticket case)
- [x] `PurgeScheduler` in `src/main/java/site/asm0dey/calit/scheduler/`
- [x] Full suite green (1220/1220, `mvn test`)
- [x] Commit

## Summary of Changes

Added `PurgeScheduler` (`src/main/java/site/asm0dey/calit/scheduler/PurgeScheduler.java`), a daily
`@Scheduled` job that deletes: `email_outbox` rows sent or dead more than 30 days ago (never a row
still inside its retry window); `password_reset_token` / `login_ticket` rows more than 1 day past
their own expiry. Plain `DELETE ... WHERE` — no `SELECT ... FOR UPDATE SKIP LOCKED` claim needed,
since a purge has nothing to hand back on failure and two replicas racing the same predicate is
naturally safe.

Verified against the real entities before writing: `EmailOutbox` marks a row dead by nulling
`nextAttemptAt` (never re-nulled/reset on send — `OutboxScheduler` only stamps `sentAt`), so the
sent-cutoff and dead-cutoff predicates are independent and a row still due for retry can never match
either. `PasswordResetToken` / `LoginTicket` both use `expiresAt` and a real `userId` FK cascading
from `app_user` (admin id 1).

Added `TokenFixtures` (`src/test/java/site/asm0dey/calit/privacy/TokenFixtures.java`, public,
following `ErasureFixtures`/`ChannelFixtures` conventions) and extended the brief's
`PurgeSchedulerTest` with two cases it didn't cover: a dead `email_outbox` row younger than 30 days
survives, and an expired `LoginTicket` (not just `PasswordResetToken`) is purged a day after expiry
while a fresh one survives.

Full suite: 1220/1220 passing, `BUILD SUCCESS`, no unrelated failures.
