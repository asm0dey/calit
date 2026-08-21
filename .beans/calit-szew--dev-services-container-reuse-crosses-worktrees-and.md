---
# calit-szew
title: Dev Services container reuse crosses worktrees and breaks on migration drift
status: todo
type: bug
priority: high
created_at: 2026-08-21T16:59:21Z
updated_at: 2026-08-21T16:59:21Z
---

`quarkus.datasource.devservices.reuse=true` makes Testcontainers match a parked Postgres container by label, and that label does not distinguish git worktrees. Run `mvn test` in a worktree whose branch has migrations through V26 while another worktree has left a V27 container parked, and Quarkus refuses to boot:

```
FlywayValidateException: Detected applied migration not resolved locally: 27
```

Hit while fixing calit-gsl7 on `fix/relative-test-dates` (branch has V26) with `feat/per-meeting-type-write-override` (V27) checked out in a sibling worktree. Worked around per-run with `-Dquarkus.datasource.devservices.reuse=false`; nothing committed.

This will bite anyone running the suite in two worktrees, which is increasingly normal here — and the failure mode is a confusing boot error, not an obvious 'wrong container' message.

- [ ] Decide the fix: scope the reuse label per branch/worktree, or turn reuse off in `%test` and accept the cold-start cost
- [ ] If reuse stays on, document the failure signature and the `-Dquarkus.datasource.devservices.reuse=false` escape hatch where someone will find it (CLAUDE.md test section)
