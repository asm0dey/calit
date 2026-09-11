---
# calit-0e98
title: Confirmation page says 'we'll keep trying' for mail that was already given up on
status: todo
type: bug
priority: low
created_at: 2026-09-11T07:34:48Z
updated_at: 2026-09-11T07:34:48Z
---

Follow-up from #195 (PR #207), raised by the final whole-branch review.

pub_conf_email_failed reads "...we'll keep trying." but MailHealth.undeliveredFor() returns true for BOTH a row still in backoff and a row that is dead (next_attempt_at IS NULL, attempt-capped or deadline-expired). For a dead row we are not, in fact, still trying.

In practice this only misfires because of the documented address-keyed correlation: on a fresh booking the row is always retrying, so the sentence is wrong only when an OLDER dead letter exists for the same address. Low impact, but it is the same class of "true in one state, false in another" defect that cost this branch two review rounds.

- [ ] Either split the copy on whether the row is still scheduled, or reword to something true in both states
