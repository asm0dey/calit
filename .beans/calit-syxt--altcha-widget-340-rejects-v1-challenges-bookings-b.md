---
# calit-syxt
title: ALTCHA widget 3.4.0 rejects v1 challenges; bookings blocked
status: completed
type: bug
priority: critical
created_at: 2026-10-20T13:32:26Z
updated_at: 2026-10-20T13:57:45Z
---

Widget 3.4.0 v1-challenge detector is `typeof!!e&&"object"===e` (always false), so the server's v1 challenge is never converted and the widget throws 'Challenge validation failed.' Shipped in v1.28.0 via #255. Fixed upstream in 3.4.1 (npm) but not yet a WebJar on Central.

- [x] Pin org.webjars.npm:altcha to 3.3.0
- [x] Renovate rule skipping 3.4.0
- [x] Regression test
- [x] Full suite green, PR (#259)

## Summary of Changes

Pinned org.webjars.npm:altcha to 3.3.0 (3.4.0's v1-challenge check is always false), Renovate skips 3.4.0, regression test in AltchaStaticAssetTest. Merged as #259, released in 1.28.1.
