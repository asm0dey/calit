---
# calit-y8lm
title: EmailService has two pre-existing duplicated blocks
status: completed
type: task
priority: low
created_at: 2026-09-12T13:01:54Z
updated_at: 2026-09-12T15:30:18Z
---

IntelliJ's clone detector (com.jetbrains.clones.DuplicateInspection) reports two duplicate pairs in EmailService that exist on main and predate the outbound-notifications branch:

- handleConfirmed vs handleApproved — ~17 identical lines building Templates.confirmation(...), differing only in the subject key (email_confirmed_subject vs email_approved_subject). Verified byte-identical in git show main:...EmailService.java.
- a 5-line pair around the guest decline/remove paths (lines 659-663 vs 673-677 on the outbound-notifications branch).

Deliberately NOT fixed on the outbound-notifications branch: unrelated to notification channels, and a 'while I was here' cleanup there would widen a feature PR's diff for no reviewer benefit.

- [x] Collapse handleConfirmed/handleApproved onto one private helper parameterised by the subject-key function
- [x] Look at the 659-663 / 673-677 pair and decide whether it is worth collapsing or is coincidental shape
- [x] Re-run the clone scan afterwards to confirm both pairs are gone
- [x] Full suite green (these are live email paths — behaviour must not shift)

## Summary of Changes

- Extracted `private void sendConfirmation(BookingSnapshot l, Function<Locale, String> subjectForLocale)` in EmailService.java. `handleConfirmed` and `handleApproved` now each reduce to a load+null-check+one-line call, passing their own owner-facing subject supplier (`email_confirmed_subject` / `email_approved_subject`). The guest-invite tail in both keeps `email_confirmed_subject` unconditionally, per the javadoc on the new helper. `handleApproved`'s explanatory comment about group bookings never firing `BookingApproved` is preserved verbatim.
- Extracted `private void sendGuestCancelMail(BookingSnapshot l, BookingGuest g, String subject, Locale locale)` in EmailService.java, replacing the byte-identical 5-line `mailSender.send(...)` block at all three call sites (`sendGuestCancels`'s loop, `handleGuestRemoved`, `handleGuestDeclined` step 1). Each caller still supplies its own subject (the loop's parameter in `sendGuestCancels`, `email_cancelled_subject` in the other two). Javadoc documents that the .ics is omitted when Google notifies the guest natively.
- Full suite: 1126 tests, 0 failures, 0 errors (`./mvnw -o test`).
- Re-ran `com.jetbrains.clones.DuplicateInspection` over EmailService.java: both originally-flagged pairs are gone. It now reports one residual pair (lines 635-639 vs 644-648) — the shared `BookingSnapshot`/`BookingGuest` load-and-null-check preamble in `handleGuestRemoved`/`handleGuestDeclined`, which was always byte-identical but previously reported as part of the larger (now-collapsed) mailSender.send match. Left alone as out of this bean's explicit scope; flagged for a possible follow-up bean if wanted.

How to re-run the scan: steroid_execute_code, instantiate com.jetbrains.clones.DuplicateInspection (a LocalInspectionTool) and call checkFile(psiFile, InspectionManager.getInstance(project), false) per file inside smartReadAction.
