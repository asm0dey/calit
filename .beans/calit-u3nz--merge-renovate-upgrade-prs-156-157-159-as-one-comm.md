---
# calit-u3nz
title: 'Merge Renovate upgrade PRs #156 #157 #159 as one commit'
status: in-progress
type: task
created_at: 2026-08-26T21:47:39Z
updated_at: 2026-08-26T21:47:39Z
---

Squash the three open Renovate PRs (quarkus 3.39.1, liberica jdk-26-musl digest, alpaquita stream-musl digest) into a single commit on main, run the suite, push, close the PRs.

- [x] Apply the three diffs to main
- [x] Run full mvn test (green)
- [ ] Commit as one commit and push main
- [ ] Close PRs #156 #157 #159 referencing the commit
