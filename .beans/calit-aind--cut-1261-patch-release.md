---
# calit-aind
title: Cut 1.26.1 patch release
status: completed
type: task
priority: normal
created_at: 2026-09-17T20:43:07Z
updated_at: 2026-09-17T20:54:57Z
---

Only change since v1.26.0 is the non-major dependency bump to Quarkus 3.39.4 (#227). No open PRs.

- [x] Wait for CI green on main head (e8c038cf, all jobs ✓)
- [x] Add 1.26.1 changelog section on docs-site
- [x] Bump README example image tags to 1.26.1
- [x] release: 1.26.1 commit on main + v1.26.1 tag
- [x] Push (with user permission)

## Summary of Changes

Cut 1.26.1. Only change since v1.26.0 was #227 (Quarkus 3.39.3 -> 3.39.4). Open PR #226 (Renovate digest pin of our own image in docker-compose.yml) was closed by the user first.

- `release: 1.26.1` (63355a37) on main: README image tags 1.26.0 -> 1.26.1, pom stays on `dev` revision.
- Tag `v1.26.1` pushed, release build running.
- `docs(changelog): cut 1.26.1` (5b94f96b) pushed straight to docs-site.
