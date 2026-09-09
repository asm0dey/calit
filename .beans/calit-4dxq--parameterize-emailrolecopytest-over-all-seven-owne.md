---
# calit-4dxq
title: Parameterize EmailRoleCopyTest over all seven owner-facing templates
status: todo
type: task
priority: normal
created_at: 2026-09-09T22:16:20Z
updated_at: 2026-09-09T22:16:20Z
---

After #196, only `confirmation` and `requested` have template-level render tests — they are the only two `EmailRoleCopyTest` injects. The other five (`reminder`, `reschedule`, `updated`, `declined`, `cancellation`) are covered by a one-off `grep -L` sweep, Qute's build-time parameter validation, and a human having read all eight call sites once.

That last part is the weak link: every argument at those call sites is a `String`, so a swapped `inviteeName`/`inviteeEmail` compiles cleanly and silently mails a `mailto:` pointing at a person's name. Two reviewers audited it by hand and found no swap — but an audit is a snapshot, not a guarantee against the next edit.

Fix (~15 lines): add the five missing `@Location` injections and one `@ParameterizedTest` asserting, for each of the seven templates, that the owner copy contains the mailto line and the invitee copy does not. Makes the grep sweep redundant and turns a one-time audit into a standing check.

While there: one assertion rendering an address containing a double quote and checking it comes back escaped would pin the HTML escaping of interpolated email-body values, which nothing currently tests.

Recommended by the final whole-branch review of #196.
