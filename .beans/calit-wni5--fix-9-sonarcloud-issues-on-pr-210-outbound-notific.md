---
# calit-wni5
title: 'Fix 9 SonarCloud issues on PR #210 (outbound notifications)'
status: completed
type: task
priority: normal
created_at: 2026-09-12T16:33:35Z
updated_at: 2026-09-12T16:40:01Z
---

Fix S7467 (unnamed exception var x3), S2925 (Thread.sleep suppressions x4), S3776 (ChannelAdmin.save cognitive complexity), S8786 (regex backtracking in test). Branch outbound-notifications, PR #210.

## Summary of Changes

- S7467 (3x): unnamed `_` for unused caught exceptions in `ChannelPolicy.resolvesPrivate` (IllegalArgumentException, UnknownHostException) and `ChannelAdmin.parseId` (NumberFormatException), following the `AdminResource.ownerZoneId()` precedent.
- S3776: extracted `ChannelAdmin.save()` into `resolveUrl(submitted, existing)` (keepStored / looksRedacted / policy.check, same order) and `applyLabel(row, rawLabel, url)` (default + truncate). Behavior and property tests unchanged.
- S2925 (4x): `@SuppressWarnings("java:S2925")" + one-line justification comment on each polling helper (ChannelAllowlistDeliveryTest.awaitFailureStamp, ChannelDeliveryTest.await, PendingExpiryChannelDeliveryTest.awaitStamp, ReminderChannelDeliveryTest.awaitStamp) — left per-test rather than hoisted, per task guidance.
- S8786: replaced backtracking `replaceAll(".*value=\"([^\"]+)\".*", "$1")" in ChannelSettingsPageTest with a compiled `Pattern`/`Matcher.find()` helper `extractValue`.
- Full suite: 1130 tests, 0 failures, 0 errors (no regression vs 1129 baseline).
