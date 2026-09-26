---
# calit-d867
title: Cut 1.27.0 release
status: completed
type: task
priority: normal
created_at: 2026-09-26T10:06:19Z
updated_at: 2026-09-26T10:22:53Z
---

Quarkus 3.39.5 security update + signed-in home redirect (#230, V36) + /lang 500 fix (#234).

- [ ] CI green on main head
- [ ] Changelog: add missing Unreleased bullets (#229/#231/#232/#233/#234), cut 1.27.0 on docs-site
- [x] Bump README example image tags to 1.27.0
- [x] release: 1.27.0 commit on main + v1.27.0 tag, push

## Summary of Changes

Cut 1.27.0: Quarkus 3.39.5 security update, signed-in home redirect (#230, V36), /lang 500 fix (#234).

- `release: 1.27.0` (32eb5c16) on main, tagged `v1.27.0`; README image tags bumped, pom stays on `dev`.
- `docs(changelog): cut 1.27.0` (b623c7a4) on docs-site.
- Release run green: JVM + native images for amd64/arm64, multi-arch manifests, GitHub Release published.
