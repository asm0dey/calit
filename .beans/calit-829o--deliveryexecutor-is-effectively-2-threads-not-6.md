---
# calit-829o
title: DeliveryExecutor is effectively 2 threads, not 6
status: todo
type: bug
priority: normal
created_at: 2026-09-12T16:15:36Z
updated_at: 2026-09-12T16:15:36Z
---

`DeliveryExecutor` (added in the outbound-notifications final fix wave) declares `CORE_THREADS = 2`, `MAX_THREADS = 6` and an `ArrayBlockingQueue(1000)`. `ThreadPoolExecutor` only grows past the core size once the queue is FULL, so with a 1000-slot queue the pool never exceeds 2 threads until 1000 deliveries are already backed up — the documented 6 is unreachable in practice.

Failure shape: two tenants each have a black-holed channel; each send blocks for the documented ~33s (3 attempts x 10s + 1s + 2s backoff). A 50-reminder tick's deliveries for every other tenant queue behind those two threads and land minutes late. The stated goal — never park an HTTP request thread — is still fully met, and the bounded queue still caps memory. This is head-of-line latency, not a hang.

- [ ] Either set core = max = 6, or keep core 2 with `allowCoreThreadTimeOut(true)` and a smaller queue so the pool actually grows
- [ ] Include the channel id in the queue-overflow WARN — today an operator cannot tell which delivery was dropped
- [ ] `ReminderScheduler:76` javadoc still claims the expiry tick fires `BookingDeclined`; it now dispatches explicitly instead
- [ ] `ReminderDue` remains in `booking/events` with zero observers — it is only `EmailService.handleReminder`'s parameter type. Consider `handleReminder(Long)` and deleting the record
