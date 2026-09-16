---
# calit-d0s4
title: Invitee erasure routes, pages and copy
status: completed
type: task
priority: normal
created_at: 2026-09-16T14:33:11Z
updated_at: 2026-09-16T14:47:00Z
parent: calit-l3fk
---

Task 5: GET/POST /booking/{token}/erase, eraseConfirm/erased templates, manage-hub button, owner-side erased placeholder, i18n

## Summary of Changes

- Added GET/POST `/booking/{manageToken}/erase` to `PublicResource` (confirm page, then
  `PrivacyService.eraseByManageToken` + done page), gated by `PrivacyConfig.inviteeErasureEnabled()`
  via a `requireErasableBooking` helper shared by both routes.
- 404'd every other invitee manage-token route (`manage`, `invite.ics`, `cancel` GET+POST,
  `reschedule`, `edit-details`) once `booking.isErased()`.
- New templates `PublicResource/eraseConfirm.html` and `PublicResource/erased.html`; `manage.html`
  gained the erase button (or the operator-contact notice when the toggle is off).
- Owner-side placeholder `(erased at the invitee's request)` in `AdminResource/dashboard.html`,
  `pending.html`, `manageBooking.html` wherever an invitee name/email was rendered raw.
- 24 new `@Message` keys (23 `AppMessages`, 1 `AdminMessages`) with German translations; Hebrew
  deliberately deferred per the epic (Task 12 tracking issue).
- Extracted `ErasureFixtures` (public, `privacy` package) from `BookingErasureTest`'s seed helper;
  `seedPastBooking()` now returns the manage token, `seedPastBookingId()` the id, per Ruling R2.
  Also seeds `OwnerSettings` for owner 1 (route tests render real pages, not just call
  `PrivacyService` directly) — an adaptation beyond the brief, reported in task-5-report.md.
- New `InviteeErasureRouteTest` and `InviteeErasureDisabledTest`.
- Adaptation beyond the brief's file list: extended `MultiHostMessageParityTest` with a
  Hebrew-deferral exemption set (German stays fully enforced) — the pre-existing parity test
  didn't anticipate the epic's Hebrew-deferred policy and would otherwise turn every task from
  here on red. Flagged for controller review.
- Full suite green: 1171/1171.
