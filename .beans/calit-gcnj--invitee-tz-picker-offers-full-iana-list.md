---
# calit-gcnj
title: Invitee tz-picker offers full IANA list
status: completed
type: feature
priority: normal
created_at: 2026-07-31T09:15:49Z
updated_at: 2026-07-31T09:16:47Z
---

Public booking-page tz-picker sources full browser IANA list via Intl.supportedValuesOf('timeZone'), curated fallback for pre-2022 browsers. Lets invitees pick any zone e.g. Asia/Jerusalem.

- [x] Add CALIT_TZ_FULL_LIST + supportedValuesOf to Layout.TZ_SCRIPT
- [x] Add test assertions in LayoutLocaleMarkerTest
- [x] Run LayoutLocaleMarkerTest (green)
- [x] Run tz-marker regression tests
- [x] Commit

## Summary of Changes

`Layout.TZ_SCRIPT` now builds the invitee tz-picker `ZONES` from `Intl.supportedValuesOf('timeZone')` (full canonical IANA list) instead of the 19 hardcoded zones. The old curated array is kept as a `catch` fallback for pre-2022 browsers. New stable markers `CALIT_TZ_FULL_LIST` + `supportedValuesOf` asserted in `LayoutLocaleMarkerTest`. No server/Java-signature change. 20 tz-marker tests green (LayoutLocaleMarkerTest, BookPageTest, BookingPostTest, ManageBookingTest).
