---
# calit-z61x
title: 'Land post-1.25.0 leftovers: bean completion, cache plan doc, surefire argLine'
status: completed
type: task
priority: normal
created_at: 2026-09-15T10:56:15Z
updated_at: 2026-09-15T10:57:01Z
---

Three uncommitted leftovers after PR #215 merged:
- .beans/calit-2usk--release-1250.md completion edit (status + summary)
- docs/superpowers/plans/2026-09-13-release-image-cache-reuse.md (plan behind #215, never committed)
- pom.xml surefire <argLine> now prefixed with @{argLine} so Quarkus's --add-opens/--add-exports flags reach the test fork instead of being dropped

- [x] Branch from origin/main
- [x] Commit docs/bean + pom separately
- [x] Open PR, let CI run the suite

## Summary of Changes

Branched `chore/post-1250-leftovers` from origin/main (PR #215 was already merged as 22eead30).

- 938ec78d `docs:` release bean completion + the image-cache plan doc
- f8e1d8ee `build:` surefire `<argLine>` prefixed with `@{argLine}`, restoring the three --add-opens/--add-exports flags Quarkus supplies
- PR #217 open, CI runs the suite

`pr_description.md` left untracked on purpose: out-of-band artifact for the already-merged #215.
