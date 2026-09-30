---
# calit-qobm
title: Invalid title/description edits via manage link return a bare untranslated 422
status: completed
type: bug
priority: normal
created_at: 2026-09-29T18:52:11Z
updated_at: 2026-09-30T09:24:58Z
---

Spec: UC-002 A9. PublicResource.editDetails (:774-800) doesn't catch BookingValidationException from updateDetails -> validateDetailBounds (BookingService.java:1255-1261, hard-coded English). BookingValidationMapper returns plain-text 422. Guest-list errors (guestsFor :594) same. Owner-side AdminResource.ownerEditDetails (:2198-2222) same. Booking create (PublicResource:580) already re-renders correctly.

- [x] Catch in editDetails and ownerEditDetails, re-render manage page with error
- [x] Message keys instead of English text (+ de/he)
- [x] Tests

## Summary of Changes

`BookingValidationException` gained an optional `messageKey`; the title/description bounds use `pub_edit_error_title_too_long` / `pub_edit_error_description_too_long` (de/he). Invitee `editDetails` and owner `ownerEditDetails` catch it and re-render the manage hub with the error above the form, keeping what was typed. Guest lists never threw (bad entries are dropped silently), so nothing to do there. Other BookingValidationException texts on the create path are still English-only. Tests: InviteeEditDetailsTest, OwnerEditDetailsTest; the old pinned-422 test now expects the form error.
