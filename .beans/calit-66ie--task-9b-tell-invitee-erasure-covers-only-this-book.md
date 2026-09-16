---
# calit-66ie
title: 'Task 9b: tell invitee erasure covers only this booking'
status: completed
type: task
priority: normal
created_at: 2026-09-16T17:44:25Z
updated_at: 2026-09-16T18:09:30Z
parent: calit-l3fk
---

Add pub_erase_not_linked/links_expire/links_forever copy to eraseConfirm.html and erased.html, computed owner retention window.


## Summary of Changes

- Added three AppMessages keys: `pub_erase_not_linked`, `pub_erase_links_expire(int days)`, `pub_erase_links_forever`, with German translations in `msg_de.properties`; Hebrew deferred (added to `HE_DEFERRED_APP_KEYS` in `MultiHostMessageParityTest`, R10).
- Added `PublicResource.erasureWindowDaysFor(Booking)`: owner's `bookingRetentionDays` override, else the instance default (`PrivacyConfig.bookingRetentionDays()`), else null (keep forever). Computed before erasure in the POST `/erase` handler (booking row still has `ownerId` at that point) and in the GET confirm page handler.
- Passed the nullable `Integer erasureWindowDays` to `Templates.eraseConfirm` and `Templates.erased`; both templates render `pub_erase_not_linked` followed by `pub_erase_links_expire` (days set) or `pub_erase_links_forever` (null), wrapped in a `<!-- CALIT_ERASE_NOT_LINKED -->` marker paragraph — in eraseConfirm.html after the description/cancels-first text and before the meeting details list, in erased.html after the per-destination outcome list.
- Added 3 new tests to `InviteeErasureRouteTest`: default-forever wording, 30-day owner-override wording, and marker presence on the POST done page. Ran `InviteeErasureRouteTest` + `MultiHostMessageParityTest` (12/12 green), then the full suite (1211/1211, BUILD SUCCESS).

- [x] Add English + German copy keys
- [x] Compute owner retention window in PublicResource
- [x] Wire into eraseConfirm.html and erased.html with CALIT_ERASE_NOT_LINKED marker
- [x] Add/extend tests, register Hebrew deferral
- [x] Full suite green



## Fix round 1 (task review)

- Clamped OwnerSettings.retentionDaysOrDefault (and added OwnerSettings.clampDays static helper)
  to PrivacyConfig.MAX_RETENTION_DAYS, matching RetentionScheduler's SQL LEAST(...) cap. Routed
  PublicResource.erasureWindowDaysFor's missing-owner-settings-row branch through the same clamp.
- Added test: oversized owner override (99,999,999) renders clamped to 36500 days on the confirm page.
- Added test: no owner_settings row at all -> confirm page still renders 200 with the 'keep forever' copy (verified it does NOT 404; documented in the test/report).
- [x] Fix round 1 addressed
