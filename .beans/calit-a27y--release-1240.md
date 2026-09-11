---
# calit-a27y
title: Release 1.24.0
status: in-progress
type: task
priority: normal
created_at: 2026-09-11T08:31:28Z
updated_at: 2026-09-11T08:45:04Z
---

Cut minor release 1.24.0 (from 1.23.0). Follows the release ritual in CLAUDE.md.

- [x] Full `mvn test` green on main (via CI run 34579537467, Build & test (Maven): success)
- [x] main CI green after merging #204 and #207 (tests, jvm+native images on amd64+arm64, both manifests)
- [x] Bump pom.xml 1.23.0 -> 1.24.0
- [x] Bump README example image tags
- [ ] `release: 1.24.0` committed to main
- [ ] Tag v1.24.0 pushed
- [ ] docs-site: promote `## Unreleased` -> `## 1.24.0` with a summary paragraph
