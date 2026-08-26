---
# calit-u3nz
title: 'Merge Renovate upgrade PRs #156 #157 #159 as one commit'
status: completed
type: task
priority: normal
created_at: 2026-08-26T21:47:39Z
updated_at: 2026-08-26T21:53:07Z
---

Squash the three open Renovate PRs (quarkus 3.39.1, liberica jdk-26-musl digest, alpaquita stream-musl digest) into a single commit on main, run the suite, push, close the PRs.

- [x] Apply the three diffs to main
- [x] Run full mvn test (green)
- [x] Commit as one commit and push main
- [ ] Close PRs #156 #157 #159 referencing the commit

- [x] Closed PRs #156 #157 #159 #160 #161

## Summary of Changes

Squashed all five open Renovate PRs onto main as two commits (the last two PRs opened while the suite was running):

- 9eac8a4 — quarkus.platform.version 3.38.3 -> 3.39.1 (#156), alpaquita-linux-base:stream-musl digest (#157), liberica-runtime-container:jdk-26-musl digest (#159). Full suite green on 3.39.1: 1036 tests, 0 failures, 0 errors.
- 6936fb0 — postgres:18 digest (#161), hardened-liberica jre-distroless-musl digest (#160). Digest-only, no Java change.

All five PRs closed with a reference to the commit; Renovate branches deleted. CI running on main.
