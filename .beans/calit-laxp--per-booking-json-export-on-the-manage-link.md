---
# calit-laxp
title: Per-booking JSON export on the manage link
status: completed
type: task
priority: normal
created_at: 2026-09-16T15:08:11Z
updated_at: 2026-09-16T15:15:08Z
parent: calit-l3fk
---

Task 6: PrivacyService.exportBooking, GET /booking/{manageToken}/data route, manage.html download link

## Summary of Changes

- `PrivacyService.exportBooking(manageToken)` — Art. 15 JSON export, keyed by the manage token
  (booking/invitee/guests, matching the brief's shape). 404 message omits the token (bearer
  credential) per the task interface note, following the existing `eraseByManageToken` pattern
  rather than the brief's literal "No booking for token " + manageToken text.
- `GET /booking/{manageToken}/data` on `PublicResource` — JSON, `Content-Disposition: attachment`.
- `manage.html` — download link placed right after the erase link/disabled-notice block,
  unconditional (not gated on `erasureEnabled` — the toggle governs erasure, not access).
- `pub_manage_download_data` added to `AppMessages` + `msg_de.properties`, and to
  `MultiHostMessageParityTest.HE_DEFERRED_APP_KEYS` per R10 (Hebrew deferred, German shipped).
- Adaptation: the brief's `BookingGuest.<BookingGuest>allForBooking(b.id)` type witness does not
  compile — `allForBooking` is a concrete (non-generic) method already declared to return
  `List<BookingGuest>`. Called it plain: `BookingGuest.allForBooking(b.id)`.
- `BookingExportTest` (3 tests, verbatim from the brief) — export shape, erased-booking 404,
  unknown-token 404.
- Full suite: `mvn test` -> 1175 tests, 0 failures, 0 errors, BUILD SUCCESS.
