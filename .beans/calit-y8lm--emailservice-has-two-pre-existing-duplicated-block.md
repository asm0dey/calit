---
# calit-y8lm
title: EmailService has two pre-existing duplicated blocks
status: todo
type: task
priority: low
created_at: 2026-09-12T13:01:54Z
updated_at: 2026-09-12T13:01:54Z
---

IntelliJ's clone detector (com.jetbrains.clones.DuplicateInspection) reports two duplicate pairs in EmailService that exist on main and predate the outbound-notifications branch:

- handleConfirmed vs handleApproved — ~17 identical lines building Templates.confirmation(...), differing only in the subject key (email_confirmed_subject vs email_approved_subject). Verified byte-identical in git show main:...EmailService.java.
- a 5-line pair around the guest decline/remove paths (lines 659-663 vs 673-677 on the outbound-notifications branch).

Deliberately NOT fixed on the outbound-notifications branch: unrelated to notification channels, and a 'while I was here' cleanup there would widen a feature PR's diff for no reviewer benefit.

- [ ] Collapse handleConfirmed/handleApproved onto one private helper parameterised by the subject-key function
- [ ] Look at the 659-663 / 673-677 pair and decide whether it is worth collapsing or is coincidental shape
- [ ] Re-run the clone scan afterwards to confirm both pairs are gone
- [ ] Full suite green (these are live email paths — behaviour must not shift)

How to re-run the scan: steroid_execute_code, instantiate com.jetbrains.clones.DuplicateInspection (a LocalInspectionTool) and call checkFile(psiFile, InspectionManager.getInstance(project), false) per file inside smartReadAction.
