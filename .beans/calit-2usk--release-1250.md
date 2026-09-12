---
# calit-2usk
title: Release 1.25.0
status: in-progress
type: task
priority: normal
created_at: 2026-09-12T22:10:25Z
updated_at: 2026-09-12T22:19:04Z
---

Cut minor release 1.25.0 (from 1.24.0). Follows the release ritual in CLAUDE.md.

Minor, not patch: `## Unreleased` carries a new user-facing feature (#210 outbound notification
channels — new table V32, new `/me/settings` section, three new `NOTIFY_*` env vars) plus #211
(V33 `default_enabled`, Send test route move).

- [x] main CI green on bfcb398f (run 34721770771)
- [x] Bump pom.xml 1.24.0 -> 1.25.0
- [x] Bump README example image tags
- [ ] `release: 1.25.0` committed to main
- [ ] Tag v1.25.0 pushed
- [ ] Release build green, images resolve on ghcr.io
- [ ] docs-site: promote `## Unreleased` -> `## 1.25.0` with a summary paragraph
