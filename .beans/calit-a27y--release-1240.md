---
# calit-a27y
title: Release 1.24.0
status: completed
type: task
priority: normal
created_at: 2026-09-11T08:31:28Z
updated_at: 2026-09-11T08:59:06Z
---

Cut minor release 1.24.0 (from 1.23.0). Follows the release ritual in CLAUDE.md.

- [x] Full `mvn test` green on main (via CI run 34579537467, Build & test (Maven): success)
- [x] main CI green after merging #204 and #207 (tests, jvm+native images on amd64+arm64, both manifests)
- [x] Bump pom.xml 1.23.0 -> 1.24.0
- [x] Bump README example image tags
- [x] `release: 1.24.0` committed to main (25baa947)
- [x] Tag v1.24.0 pushed
- [x] docs-site: promote `## Unreleased` -> `## 1.24.0` with a summary paragraph (4bc0c72d)

## Summary of Changes

Cut 1.24.0 from 1.23.0. Five user-facing changes: broken email is no longer invisible (#207/#195), owner notifications name the invitee (#196) and say who cancelled (#201), per-meeting-type name and guests fields (#184), and the availability editor day buttons moved beside the times (#204/#197).

- main: 25baa947 `release: 1.24.0` (pom 1.23.0 -> 1.24.0, README image tags)
- tag: v1.24.0, build 34580735053 green — Maven tests, jvm + native images on amd64 and arm64, both manifests merged, GitHub Release created
- ghcr.io/asm0dey/calit: 1.24.0, 1.24.0-native and latest all resolve (HTTP 200)
- docs-site: 4bc0c72d `docs(changelog): cut 1.24.0`, Pages deploy succeeded
- PRs #204 and #207 squash-merged, both branches deleted
