---
# calit-242z
title: 'email_outbox: no index on the two MailHealth queries, and the table is never pruned'
status: todo
type: task
priority: low
created_at: 2026-09-11T07:34:48Z
updated_at: 2026-09-11T07:34:48Z
---

Follow-up from #195 (PR #207), raised by the final whole-branch review.

MailHealth.deadLetters() filters `next_attempt_at IS NULL AND sent_at IS NULL`, which is the exact complement of idx_email_outbox_due's partial predicate, so it can only ever be a seq scan — and it runs on every dashboard render.

MailHealth.undeliveredFor(recipient) filters `recipient = ? AND sent_at IS NULL` with no index on recipient, and it runs on the booking POST hot path.

Neither matters at realistic table sizes today, but OutboxScheduler only ever sets sent_at — nothing deletes — so email_outbox is append-only for the lifetime of the deployment. After one long outage on a busy instance both queries degrade together.

- [ ] Decide whether to add a partial index for each query or to prune sent rows past a retention window
- [ ] If pruning: decide the window and whether dead letters are exempt (the dashboard counts them)
