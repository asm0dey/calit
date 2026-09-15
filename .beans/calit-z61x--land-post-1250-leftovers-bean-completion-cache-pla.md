---
# calit-z61x
title: 'Land post-1.25.0 leftovers: bean completion, cache plan doc, surefire argLine'
status: in-progress
type: task
created_at: 2026-09-15T10:56:15Z
updated_at: 2026-09-15T10:56:15Z
---

Three uncommitted leftovers after PR #215 merged:
- .beans/calit-2usk--release-1250.md completion edit (status + summary)
- docs/superpowers/plans/2026-09-13-release-image-cache-reuse.md (plan behind #215, never committed)
- pom.xml surefire <argLine> now prefixed with @{argLine} so Quarkus's --add-opens/--add-exports flags reach the test fork instead of being dropped

- [ ] Branch from origin/main
- [ ] Commit docs/bean + pom separately
- [ ] Open PR, let CI run the suite
