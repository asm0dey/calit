---
# calit-2ywl
title: Release 1.21.0
status: completed
type: task
priority: normal
created_at: 2026-08-22T16:06:33Z
updated_at: 2026-08-22T16:15:11Z
---

Cut minor release 1.21.0 (from 1.20.2). Follows the documented release ritual in CLAUDE.md.

- [x] Full `mvn test` green (935 tests, 0 failures)
- [x] Bump pom.xml 1.20.2 -> 1.21.0
- [x] Bump README example image tags
- [x] `release: 1.21.0` committed straight to main (b420a69) — no PR; PR #149 opened then closed on the maintainer's correction
- [x] Tag v1.21.0 pushed
- [x] docs-site: promoted `## Unreleased` -> `## 1.21.0` (00779ea, pushed)

## Summary of Changes

Cut 1.21.0 from 1.20.2.

- Full suite green first: 935 tests, 0 failures, 0 errors.
- `pom.xml` 1.20.2 -> 1.21.0; README example image tags -> `1.21.0` / `1.21.0-native`. Quarkus/Java badges already matched the pom, so no refresh needed.
- `release: 1.21.0` (b420a69) pushed directly to `main`, then tag `v1.21.0` — release commits and tags do not go through a PR on this repo.
- `docs-site`: `## Unreleased` promoted to `## 1.21.0` with a summary paragraph (00779ea). No version pins elsewhere in the docs — every Compose example uses `latest`.
- CI: tag build running for the release images; docs Pages deploy queued.
