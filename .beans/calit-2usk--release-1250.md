---
# calit-2usk
title: Release 1.25.0
status: completed
type: task
priority: normal
created_at: 2026-09-12T22:10:25Z
updated_at: 2026-09-12T22:33:19Z
---

Cut minor release 1.25.0 (from 1.24.0). Follows the release ritual in CLAUDE.md.

Minor, not patch: `## Unreleased` carries a new user-facing feature (#210 outbound notification
channels — new table V32, new `/me/settings` section, three new `NOTIFY_*` env vars) plus #211
(V33 `default_enabled`, Send test route move).

- [x] main CI green on bfcb398f (run 34721770771)
- [x] Bump pom.xml 1.24.0 -> 1.25.0
- [x] Bump README example image tags
- [x] `release: 1.25.0` committed to main (e70f4ab7)
- [x] Tag v1.25.0 pushed
- [x] Release build green (34722421776), images resolve on ghcr.io
- [x] docs-site: promote `## Unreleased` -> `## 1.25.0` with a summary paragraph (PR #213, awaiting merge)

## Summary of Changes

Cut 1.25.0 from 1.24.0. Minor rather than patch: `## Unreleased` carried a new user-facing feature (#210 outbound notification channels) alongside #211.

- main: e70f4ab7 `release: 1.25.0` (pom 1.24.0 -> 1.25.0, README image tags)
- tag: v1.25.0, build 34722421776 green — Maven tests, jvm + native images on amd64 and arm64, both manifests merged, GitHub Release created (not draft, not prerelease)
- ghcr.io/asm0dey/calit: 1.25.0, 1.25.0-native and latest all resolve (HTTP 200); both versioned tags carry linux/amd64 + linux/arm64
- docs-site: PR #213 `docs(changelog): cut 1.25.0` open, NOT merged — the published changelog still reads "Unreleased" until it is

Filed `calit-0s1x` while waiting on the build: the native-image layer never caches, because `ARG GIT_COMMIT` sits above the compile in both Dockerfiles.
