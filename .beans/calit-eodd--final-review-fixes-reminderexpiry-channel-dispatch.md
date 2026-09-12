---
# calit-eodd
title: 'Final review fixes: reminder/expiry channel dispatch, policy-skip stamp, bounded delivery pool'
status: completed
type: task
priority: normal
created_at: 2026-09-12T15:50:37Z
updated_at: 2026-09-12T16:06:18Z
---

Fix wave from the final whole-branch review of outbound-notifications.

- [x] FIX 1: explicit notifyReminder/notifyDeclined dispatch from ReminderScheduler + PendingExpiryScheduler; delete dead ReminderDue observers
- [x] FIX 2: policy-rejected channel stamps last_failure_at (sync side) + allowlist-tightened test
- [x] FIX 3a: NotifyConfig.interactiveHttp() for the inline Send test button
- [x] FIX 3b: bounded DeliveryExecutor for fireAsync instead of the shared worker pool
- [x] FIX 4: delete dead adm_settings_channels_add key + unused template ids
- [x] Full suite green

- [x] Convert all five JDK HttpServer stub tests to an ephemeral port

## Summary of Changes

- `NotificationDispatcher.notifyReminder/notifyDeclined` are explicit entry points; the dead
  `ReminderDue` observers in `NotificationDispatcher` and `EmailService` are gone (the
  test-referenced `EmailService.handleReminder` stays). `ReminderScheduler` and
  `PendingExpiryScheduler` collect the ids they actually claimed inside the claim transaction and
  dispatch after it commits.
- A policy-rejected channel now stamps `last_failure_at` through `ChannelStamp`, wrapped in
  `QuarkusTransaction.requiringNew()` — a REQUIRED interceptor inside an AFTER_SUCCESS observer
  joins an already-completed caller transaction and the UPDATE fails.
- `NotifyConfig.interactiveHttp()` (1 attempt, 4s) backs the inline "Send test"; deliveries are
  notified on the bounded `DeliveryExecutor` instead of the shared Quarkus worker pool.
- Orphan `adm_settings_channels_add` key and the unused `channel-form`/`channel-rows` ids removed.
- All five stub-server tests (four notify/scheduler + `CaptchaVerifierTurnstileTest`) bind an
  ephemeral port.

Full suite: 1129 tests, 0 failures.
